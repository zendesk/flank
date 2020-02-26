package ftl.run

import com.google.api.services.testing.model.TestMatrix
import com.google.cloud.storage.Storage
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import ftl.args.AndroidArgs
import ftl.args.IArgs
import ftl.args.IosArgs
import ftl.config.FtlConstants
import ftl.config.FtlConstants.defaultAndroidConfig
import ftl.config.FtlConstants.defaultIosConfig
import ftl.config.FtlConstants.indent
import ftl.config.FtlConstants.localhost
import ftl.config.FtlConstants.matrixIdsFile
import ftl.gc.GcStorage
import ftl.gc.GcTestMatrix
import ftl.gc.GcTesting
import ftl.gc.GcToolResults
import ftl.json.MatrixMap
import ftl.json.SavedMatrix
import ftl.reports.util.ReportManager
import ftl.util.Artifacts
import ftl.util.MatrixState
import ftl.util.ObjPath
import ftl.util.StopWatch
import ftl.util.StopWatchMatrix
import ftl.util.Utils
import ftl.util.Utils.fatalError
import ftl.util.completed
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

object TestRunner {
    val gson = GsonBuilder().setPrettyPrinting().create()!!

    fun assertMockUrl() {
        if (!FtlConstants.useMock) return
        if (!GcTesting.get.rootUrl.contains(localhost)) throw RuntimeException("expected localhost in GcTesting")
        if (!GcStorage.storageOptions.host.contains(localhost)) throw RuntimeException("expected localhost in GcStorage")
        if (!GcToolResults.service.rootUrl.contains(localhost)) throw RuntimeException("expected localhost in GcToolResults")
    }

    private suspend fun runTests(args: IArgs): Pair<MatrixMap, List<List<String>>> {
        return when (args) {
            is AndroidArgs -> AndroidTestRunner.runTests(args)
            is IosArgs -> IosTestRunner.runTests(args)
            else -> throw RuntimeException("Unknown config type")
        }
    }

    fun updateMatrixFile(matrixMap: MatrixMap, args: IArgs): Path {
        val matrixIdsPath = if (args.useLocalResultDir()) {
            Paths.get(args.localResultDir, matrixIdsFile)
        } else {
            Paths.get(args.localResultDir, matrixMap.runPath, matrixIdsFile)
        }
        matrixIdsPath.parent.toFile().mkdirs()
        Files.write(matrixIdsPath, gson.toJson(matrixMap.map).toByteArray())
        return matrixIdsPath
    }

    fun saveConfigFile(matrixMap: MatrixMap, args: IArgs): Path? {
        val configFilePath = if (args.useLocalResultDir()) {
            Paths.get(args.localResultDir, FtlConstants.configFileName(args))
        } else {
            Paths.get(args.localResultDir, matrixMap.runPath, FtlConstants.configFileName(args))
        }

        configFilePath.parent.toFile().mkdirs()
        Files.write(configFilePath, args.data.toByteArray())
        return configFilePath
    }

    /** Refresh all in progress matrices in parallel **/
    private suspend fun refreshMatrices(matrixMap: MatrixMap, args: IArgs) = coroutineScope {
        println("RefreshMatrices")

        val jobs = arrayListOf<Deferred<TestMatrix>>()
        val map = matrixMap.map
        var matrixCount = 0
        map.forEach { matrix ->
            // Only refresh unfinished
            if (MatrixState.inProgress(matrix.value.state)) {
                matrixCount += 1
                jobs += async(Dispatchers.IO) { GcTestMatrix.refresh(matrix.key, args) }
            }
        }

        if (matrixCount != 0) {
            println(indent + "Refreshing ${matrixCount}x matrices")
        }

        var dirty = false
        jobs.awaitAll().forEach { matrix ->
            val matrixId = matrix.testMatrixId

            println(indent + "${matrix.state} $matrixId")

            if (map[matrixId]?.update(matrix) == true) dirty = true
        }

        if (dirty) {
            println(indent + "Updating matrix file")
            updateMatrixFile(matrixMap, args)
        }
        println()
    }

    /** Cancel all in progress matrices in parallel **/
    private fun cancelMatrices(matrixMap: MatrixMap, args: IArgs) {
        println("CancelMatrices")

        val map = matrixMap.map
        var matrixCount = 0

        runBlocking {
            map.forEach { matrix ->
                // Only cancel unfinished
                if (MatrixState.inProgress(matrix.value.state)) {
                    matrixCount += 1
                    launch { GcTestMatrix.cancel(matrix.key, args) }
                }
            }
        }

        if (matrixCount == 0) {
            println(indent + "No matrices to cancel")
        } else {
            println(indent + "Cancelling ${matrixCount}x matrices")
        }

        println()
    }

    private fun lastGcsPath(args: IArgs): String? {
        val resultsFile = Paths.get(args.localResultDir).toFile()
        if (!resultsFile.exists()) return null

        val scheduledRuns = resultsFile.listFiles()
                .filter { it.isDirectory }
                .sortedByDescending { it.lastModified() }
        if (scheduledRuns.isEmpty()) return null

        return scheduledRuns.first().name
    }

    /** Reads in the last matrices from the localResultDir folder **/
    private fun lastArgs(args: IArgs): IArgs {
        val lastRun = lastGcsPath(args)

        if (lastRun == null) {
            fatalError("no runs found in results/ folder")
        }

        val iosConfig = Paths.get(args.localResultDir, lastRun, defaultIosConfig)
        val androidConfig = Paths.get(args.localResultDir, lastRun, defaultAndroidConfig)

        when {
            iosConfig.toFile().exists() -> return IosArgs.load(iosConfig)
            androidConfig.toFile().exists() -> return AndroidArgs.load(androidConfig)
            else -> fatalError("No config file found in the last run folder: $lastRun")
        }

        throw RuntimeException("should not happen")
    }

    /** Reads in the last matrices from the localResultDir folder **/
    private fun lastMatrices(args: IArgs): MatrixMap {
        val lastRun = lastGcsPath(args)

        if (lastRun == null) {
            fatalError("no runs found in results/ folder")
            throw RuntimeException("fatalError failed to exit the process")
        }

        println("Loading run $lastRun")
        return matrixPathToObj(lastRun, args)
    }

    /** Creates MatrixMap from matrix_ids.json file */
    fun matrixPathToObj(path: String, args: IArgs): MatrixMap {
        var filePath = Paths.get(path, matrixIdsFile).toFile()
        if (!filePath.exists()) {
            filePath = Paths.get(args.localResultDir, path, matrixIdsFile).toFile()
        }
        val json = filePath.readText()

        val listOfSavedMatrix = object : TypeToken<MutableMap<String, SavedMatrix>>() {}.type
        val map: MutableMap<String, SavedMatrix> = gson.fromJson(json, listOfSavedMatrix)

        return MatrixMap(map, path)
    }

    fun getDownloadPath(args: IArgs, blobPath: String): Path {
        val localDir = args.localResultDir
        val p = if (args is AndroidArgs)
            ObjPath.parse(blobPath) else
            ObjPath.legacyParse(blobPath)

        // Store downloaded artifacts at device root.
        return if (args.useLocalResultDir()) {
            if (args is AndroidArgs && args.keepFilePath)
                Paths.get(localDir, p.shardName, p.deviceName, p.filePathName, p.fileName)
            else
                Paths.get(localDir, p.shardName, p.deviceName, p.fileName)
        } else {
            if (args is AndroidArgs && args.keepFilePath)
                Paths.get(localDir, p.objName, p.shardName, p.deviceName, p.filePathName, p.fileName)
            else
                Paths.get(localDir, p.objName, p.shardName, p.deviceName, p.fileName)
        }
    }

    private fun fetchArtifacts(matrixMap: MatrixMap, args: IArgs) {
        println("FetchArtifacts")
        val fields = Storage.BlobListOption.fields(Storage.BlobField.NAME)

        var dirty = false
        val filtered = matrixMap.map.values.filter {
            val finished = it.state == MatrixState.FINISHED
            val notDownloaded = !it.downloaded
            finished && notDownloaded
        }

        print(indent)
        runBlocking {
            filtered.forEach { matrix ->
                launch {
                    val prefix = Storage.BlobListOption.prefix(matrix.gcsPathWithoutRootBucket)
                    val result = GcStorage.storage.list(matrix.gcsRootBucket, prefix, fields)
                    val artifactsList = Artifacts.regexList(args)

                    result.iterateAll().forEach { blob ->
                        val blobPath = blob.blobId.name
                        val matched = artifactsList.any { blobPath.matches(it) }
                        if (matched) {
                            val downloadFile = getDownloadPath(args, blobPath)

                            print(".")
                            if (!downloadFile.toFile().exists()) {
                                val parentFile = downloadFile.parent.toFile()
                                parentFile.mkdirs()
                                blob.downloadTo(downloadFile)
                            }
                        }
                    }

                    dirty = true
                    matrix.downloaded = true
                }
            }
        }
        println()

        if (dirty) {
            println(indent + "Updating matrix file")
            updateMatrixFile(matrixMap, args)
            println()
        }
    }

    /** Synchronously poll all matrix ids until they complete. Returns true if test run passed. **/
    private fun pollMatrices(matrices: MatrixMap, args: IArgs) {
        println("PollMatrices")
        val poll = matrices.map.values.filter {
            MatrixState.inProgress(it.state)
        }

        val stopwatch = StopWatch().start()
        poll.forEach {
            val matrixId = it.matrixId
            pollMatrix(matrixId, stopwatch, args, matrices)
        }
        println()

        updateMatrixFile(matrices, args)
    }

    // Used for when the matrix has exactly one test. Polls for detailed progress
    //
    // Port of MonitorTestExecutionProgress
    // gcloud-cli/googlecloudsdk/api_lib/firebase/test/matrix_ops.py
    private fun pollMatrix(matrixId: String, stopwatch: StopWatch, args: IArgs, matrices: MatrixMap) {
        var refreshedMatrix = GcTestMatrix.refresh(matrixId, args)
        val watch = StopWatchMatrix(stopwatch, matrixId)
        val runningDevices = RunningDevices(stopwatch, refreshedMatrix.testExecutions)

        while (true) {
            if (matrices.map[matrixId]?.update(refreshedMatrix) == true) updateMatrixFile(matrices, args)

            runningDevices.allRunning().forEach { nextDevice ->
                nextDevice.poll(refreshedMatrix)
            }

            // Matrix has 0 or more devices (test executions)
            // Verify all executions are complete & the matrix itself is marked as complete.
            if (runningDevices.allComplete() && refreshedMatrix.completed()) {
                break
            }

            // GetTestMatrix is not designed to handle many requests per second.
            // Sleep to avoid overloading the system.
            Utils.sleep(5)
            refreshedMatrix = GcTestMatrix.refresh(matrixId, args)
        }

        // Print final matrix state with timestamp. May be many minutes after the 'Done.' progress message.
        watch.puts(refreshedMatrix.state)
    }

    // used to update and poll the results from an async run
    suspend fun refreshLastRun(currentArgs: IArgs, testShardChunks: List<List<String>>) {
        val matrixMap = lastMatrices(currentArgs)
        val lastArgs = lastArgs(currentArgs)

        refreshMatrices(matrixMap, lastArgs)
        pollMatrices(matrixMap, lastArgs)
        fetchArtifacts(matrixMap, lastArgs)

        // Must generate reports *after* fetching xml artifacts since reports require xml
        val exitCode = ReportManager.generate(matrixMap, lastArgs, testShardChunks)
        exitProcess(exitCode)
    }

    // used to cancel and update results from an async run
    fun cancelLastRun(args: IArgs) {
        val matrixMap = lastMatrices(args)
        val lastArgs = lastArgs(args)

        cancelMatrices(matrixMap, lastArgs)
    }

    suspend fun newRun(args: IArgs) {
        println(args)
        val (matrixMap, testShardChunks) = runTests(args)

        if (!args.async) {
            pollMatrices(matrixMap, args)
            fetchArtifacts(matrixMap, args)

            val exitCode = ReportManager.generate(matrixMap, args, testShardChunks)
            exitProcess(exitCode)
        }
    }
}
