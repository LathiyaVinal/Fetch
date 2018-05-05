package com.tonyodev.fetch2.downloader

import com.tonyodev.fetch2.Download
import com.tonyodev.fetch2.Downloader
import com.tonyodev.fetch2.Error
import com.tonyodev.fetch2.Logger
import com.tonyodev.fetch2.exception.FetchException
import com.tonyodev.fetch2.getErrorFromThrowable
import com.tonyodev.fetch2.provider.NetworkInfoProvider
import com.tonyodev.fetch2.util.*
import java.io.*
import java.net.HttpURLConnection
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.ceil

class ParallelFileDownloaderImpl(private val initialDownload: Download,
                                 private val downloader: Downloader,
                                 private val progressReportingIntervalMillis: Long,
                                 private val downloadBufferSizeBytes: Int,
                                 private val logger: Logger,
                                 private val networkInfoProvider: NetworkInfoProvider,
                                 private val retryOnNetworkGain: Boolean,
                                 private val fileChunkTempDir: String) : FileDownloader {

    @Volatile
    override var interrupted = false

    @Volatile
    override var terminated = false

    @Volatile
    override var completedDownload = false

    override var delegate: FileDownloader.Delegate? = null

    private var downloadInfo = initialDownload.toDownloadInfo()

    override val download: Download
        get () {
            downloadInfo.downloaded = getProgressDownloaded()
            downloadInfo.total = total
            return downloadInfo
        }

    private var downloaded = 0L

    private val downloadedLock = Object()

    private var total = 0L

    private var averageDownloadedBytesPerSecond = 0.0

    private val movingAverageCalculator = AverageCalculator(5)

    private var estimatedTimeRemainingInMilliseconds: Long = -1

    private var executorService: ExecutorService? = null

    private var phase = Phase.IDLE

    private var actionsCounter = 0

    private val actionCountLock = Object()

    private var queuedActionsTotal = 0

    private val mergeCompletedCountLock = Object()

    private var mergeCompletedCount = 0

    @Volatile
    private var mergedBytesWritten = 0L

    private var fileChunks = emptyList<FileChuck>()

    override fun run() {
        var openingResponse: Downloader.Response? = null
        var output: OutputStream? = null
        var randomAccessFile: RandomAccessFile? = null
        try {
            val openingRequest = getRequestForDownload(initialDownload)
            openingResponse = downloader.execute(openingRequest)
            if (!interrupted && !terminated && openingResponse?.isSuccessful == true) {
                total = openingResponse.contentLength
                if (total > 0) {
                    downloadInfo.total = total
                    fileChunks = getFileChunkList(openingResponse.code, openingRequest)
                    val chunkDownloadsList = fileChunks.filter { !it.isDownloaded }
                    if (!interrupted && !terminated) {
                        downloadInfo.downloaded = getProgressDownloaded()
                        delegate?.onStarted(
                                download = downloadInfo,
                                etaInMilliseconds = estimatedTimeRemainingInMilliseconds,
                                downloadedBytesPerSecond = getAverageDownloadedBytesPerSecond())
                        executorService = Executors.newFixedThreadPool(chunkDownloadsList.size + 1)
                        downloadChunks(chunkDownloadsList)
                        waitAndPerformProgressReporting()
                        if (!interrupted && !terminated) {
                            var downloadedBytesSum = 0L
                            for (fileChuck in fileChunks) {
                                val exception = fileChuck.errorException
                                if (fileChuck.status == Status.ERROR && exception != null) {
                                    throw exception
                                }
                                fileChuck.status = Status.MERGING
                                downloadedBytesSum += fileChuck.downloaded
                            }
                            if (downloadedBytesSum == total) {
                                downloaded = total
                                downloadInfo.downloaded = getProgressDownloaded()
                                output = downloader.getRequestOutputStream(openingRequest, 0)
                                if (output == null) {
                                    randomAccessFile = RandomAccessFile(getFile(downloadInfo.file), "rw")
                                    randomAccessFile.seek(0)
                                }
                                if (!interrupted && !terminated) {
                                    mergeAllChunks(output, randomAccessFile)
                                    waitAndPerformProgressReporting()
                                    if (!interrupted && !terminated) {
                                        var mergeCount = 0
                                        for (chunk in fileChunks) {
                                            if (chunk.status == Status.MERGED) {
                                                mergeCount += 1
                                            }
                                        }
                                        val allFileChunksMerged = mergeCount == fileChunks.size
                                        if (allFileChunksMerged) {
                                            if (!terminated) {
                                                for (fileChunk in fileChunks) {
                                                    deleteTempInFilesForChunk(fileChunk)
                                                }
                                                deleteTempDirForId(initialDownload.id)
                                                downloadInfo.downloaded = total
                                                completedDownload = true
                                                phase = Phase.COMPLETED
                                                delegate?.onProgress(
                                                        download = downloadInfo,
                                                        etaInMilliSeconds = estimatedTimeRemainingInMilliseconds,
                                                        downloadedBytesPerSecond = getAverageDownloadedBytesPerSecond())
                                                delegate?.onComplete(
                                                        download = downloadInfo)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        downloadInfo.downloaded = getProgressDownloaded()
                        delegate?.saveDownloadProgress(downloadInfo)
                        if (!completedDownload && !terminated) {
                            delegate?.onProgress(
                                    download = downloadInfo,
                                    etaInMilliSeconds = estimatedTimeRemainingInMilliseconds,
                                    downloadedBytesPerSecond = getAverageDownloadedBytesPerSecond())
                        }
                        if (!terminated) {
                            for (fileChunk in fileChunks) {
                                val exception = fileChunk.errorException
                                if (fileChunk.status == Status.ERROR && exception != null) {
                                    throw exception
                                }
                            }
                        }
                    }
                    phase = Phase.IDLE
                } else {
                    throw FetchException(EMPTY_RESPONSE_BODY,
                            FetchException.Code.EMPTY_RESPONSE_BODY)
                }
            } else if (openingResponse == null && !interrupted && !terminated) {
                throw FetchException(EMPTY_RESPONSE_BODY,
                        FetchException.Code.EMPTY_RESPONSE_BODY)
            } else if (openingResponse?.isSuccessful == false && !interrupted && !terminated) {
                throw FetchException(RESPONSE_NOT_SUCCESSFUL,
                        FetchException.Code.REQUEST_NOT_SUCCESSFUL)
            } else if (!interrupted && !terminated) {
                throw FetchException(UNKNOWN_ERROR,
                        FetchException.Code.UNKNOWN)
            }
        } catch (e: Exception) {
            if (!interrupted && !terminated) {
                logger.e("FileDownloader", e)
                var error = getErrorFromThrowable(e)
                error.throwable = e
                if (retryOnNetworkGain) {
                    var disconnectDetected = !networkInfoProvider.isNetworkAvailable
                    for (i in 1..10) {
                        try {
                            Thread.sleep(500)
                        } catch (e: InterruptedException) {
                            logger.e("FileDownloader", e)
                            break
                        }
                        if (!networkInfoProvider.isNetworkAvailable) {
                            disconnectDetected = true
                            break
                        }
                    }
                    if (disconnectDetected) {
                        error = Error.NO_NETWORK_CONNECTION
                    }
                }
                downloadInfo.downloaded = getProgressDownloaded()
                downloadInfo.total = total
                downloadInfo.error = error
                if (!terminated) {
                    delegate?.onError(download = downloadInfo)
                }
            }
        } finally {
            try {
                executorService?.shutdown()
            } catch (e: Exception) {
                logger.e("FileDownloader", e)
            }
            if (openingResponse != null) {
                try {
                    downloader.disconnect(openingResponse)
                } catch (e: Exception) {
                    logger.e("FileDownloader", e)
                }
            }
            try {
                output?.close()
            } catch (e: Exception) {
                logger.e("FileDownloader", e)
            }
            try {
                randomAccessFile?.close()
            } catch (e: Exception) {
                logger.e("FileDownloader", e)
            }
            terminated = true
        }
    }

    private fun getProgressDownloaded(): Long {
        return when (phase) {
            Phase.DOWNLOADING -> {
                val actualProgress = calculateProgress(downloaded, total)
                val ninetyPercentOfTotal = (0.9F * total.toFloat())
                val downloaded = (actualProgress.toFloat() / 100.toFloat()) * ninetyPercentOfTotal
                downloaded.toLong()
            }
            Phase.MERGING -> {
                val actualProgress = calculateProgress(mergedBytesWritten, total)
                val tenPercentOfTotal = (0.1F * total.toFloat())
                val ninetyPercentOfTotal = (0.9F * total.toFloat())
                val merged = (actualProgress.toFloat() / 100.toFloat()) * tenPercentOfTotal
                (ninetyPercentOfTotal + merged).toLong()
            }
            Phase.COMPLETED -> {
                total
            }
            Phase.IDLE -> {
                -1L
            }
        }
    }

    private fun getFileChunkList(openingResponseCode: Int, request: Downloader.Request): List<FileChuck> {
        return if (openingResponseCode == HttpURLConnection.HTTP_PARTIAL) {
            val fileChunkInfo = getChuckInfo(request)
            var counterBytes = 0L
            val fileChunks = mutableListOf<FileChuck>()
            for (position in 1..fileChunkInfo.chunkCount) {
                val startBytes = counterBytes
                val endBytes = if (fileChunkInfo.chunkCount == position) {
                    total
                } else {
                    counterBytes + fileChunkInfo.bytesPerChunk
                }
                counterBytes = endBytes
                val fileChunk = FileChuck(
                        id = downloadInfo.id,
                        position = position,
                        startBytes = startBytes,
                        endBytes = endBytes,
                        file = getBytesDataFileForChunk(downloadInfo.id, position))
                fileChunk.downloaded = getSavedDownloadedForFileChunk(fileChunk)
                downloaded += fileChunk.downloaded
                if (fileChunk.startBytes + fileChunk.downloaded == fileChunk.endBytes) {
                    fileChunk.status = Status.DOWNLOADED
                }
                fileChunks.add(fileChunk)
            }
            fileChunks
        } else {
            val singleFileChunk = FileChuck(
                    id = downloadInfo.id,
                    position = 1,
                    startBytes = 0,
                    endBytes = total,
                    file = getBytesDataFileForChunk(downloadInfo.id, 1))
            singleFileChunk.downloaded = getSavedDownloadedForFileChunk(singleFileChunk)
            if (singleFileChunk.startBytes + singleFileChunk.downloaded == singleFileChunk.endBytes) {
                singleFileChunk.status = Status.DOWNLOADED
            }
            downloaded += singleFileChunk.downloaded
            listOf(singleFileChunk)
        }
    }

    private fun getChuckInfo(request: Downloader.Request): FileChunkInfo {
        val fileChunkSize = downloader.getFileChunkSize(request, total) ?: DEFAULT_FILE_CHUNK_LIMIT
        return if (fileChunkSize == DEFAULT_FILE_CHUNK_LIMIT) {
            val fileSizeInMb = total.toFloat() / 1024F * 1024F
            val fileSizeInGb = total.toFloat() / 1024F * 1024F * 1024F
            when {
                fileSizeInGb >= 1 -> {
                    val chunks = 4
                    val bytesPerChunk = ceil((total.toFloat() / chunks.toFloat())).toLong()
                    FileChunkInfo(chunks, bytesPerChunk)
                }
                fileSizeInMb >= 1 -> {
                    val chunks = 2
                    val bytesPerChunk = ceil((total.toFloat() / chunks.toFloat())).toLong()
                    FileChunkInfo(chunks, bytesPerChunk)
                }
                else -> FileChunkInfo(1, total)
            }
        } else {
            val bytesPerChunk = ceil((total.toFloat() / fileChunkSize.toFloat())).toLong()
            return FileChunkInfo(fileChunkSize, bytesPerChunk)
        }
    }

    private fun getBytesDataFileForChunk(id: Int, position: Int): String {
        return "$fileChunkTempDir/$id/$id.$position.tmp"
    }

    private fun getDownloadedInfoFileForChunk(id: Int, position: Int): String {
        return "${getBytesDataFileForChunk(id, position)}.txt"
    }

    private fun deleteTempDirForId(id: Int) {
        try {
            val file = File("$fileChunkTempDir/$id")
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
        }
    }

    private fun deleteTempInFilesForChunk(fileChunk: FileChuck) {
        try {
            val file = File(fileChunk.file)
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
        }
        try {
            val text = getFile(getDownloadedInfoFileForChunk(fileChunk.id, fileChunk.position))
            if (text.exists()) {
                text.delete()
            }
        } catch (e: Exception) {
        }
    }

    private fun getSavedDownloadedForFileChunk(fileChunk: FileChuck): Long {
        var downloaded = 0L
        val file = getFile(getDownloadedInfoFileForChunk(fileChunk.id, fileChunk.position))
        if (file.exists() && !interrupted && !terminated) {
            val bufferedReader = BufferedReader(FileReader(file))
            try {
                val string: String? = bufferedReader.readLine()
                downloaded = string?.toLong() ?: 0L
            } catch (e: Exception) {
                logger.e("FileDownloader", e)
            } finally {
                try {
                    bufferedReader.close()
                } catch (e: Exception) {
                    logger.e("FileDownloader", e)
                }
            }
        }
        return downloaded
    }

    private fun saveDownloadedInfoForFileChunk(fileChunk: FileChuck, downloaded: Long) {
        val file = getFile(getDownloadedInfoFileForChunk(fileChunk.id, fileChunk.position))
        if (file.exists() && !interrupted && !terminated) {
            val bufferedWriter = BufferedWriter(FileWriter(file))
            try {
                bufferedWriter.write(downloaded.toString())
            } catch (e: Exception) {
                logger.e("FileDownloader", e)
            } finally {
                try {
                    bufferedWriter.close()
                } catch (e: Exception) {
                    logger.e("FileDownloader", e)
                }
            }
        }
    }

    private fun getAverageDownloadedBytesPerSecond(): Long {
        if (averageDownloadedBytesPerSecond < 1) {
            return 0L
        }
        return ceil(averageDownloadedBytesPerSecond).toLong()
    }

    private fun waitAndPerformProgressReporting() {
        var reportingStopTime: Long
        var downloadSpeedStopTime: Long
        var downloadedBytesPerSecond = getProgressDownloaded()
        var reportingStartTime = System.nanoTime()
        var downloadSpeedStartTime = System.nanoTime()
        while (chunksMergingOrDownloading() && !interrupted && !terminated) {
            downloadInfo.downloaded = getProgressDownloaded()
            downloadSpeedStopTime = System.nanoTime()
            val downloadSpeedCheckTimeElapsed = hasIntervalTimeElapsed(downloadSpeedStartTime,
                    downloadSpeedStopTime, DEFAULT_DOWNLOAD_SPEED_REPORTING_INTERVAL_IN_MILLISECONDS)

            if (downloadSpeedCheckTimeElapsed) {
                downloadedBytesPerSecond = getProgressDownloaded() - downloadedBytesPerSecond
                movingAverageCalculator.add(downloadedBytesPerSecond.toDouble())
                averageDownloadedBytesPerSecond =
                        movingAverageCalculator.getMovingAverageWithWeightOnRecentValues()
                estimatedTimeRemainingInMilliseconds = calculateEstimatedTimeRemainingInMilliseconds(
                        downloadedBytes = getProgressDownloaded(),
                        totalBytes = total,
                        downloadedBytesPerSecond = getAverageDownloadedBytesPerSecond())
                downloadedBytesPerSecond = getProgressDownloaded()
                if (progressReportingIntervalMillis > DEFAULT_DOWNLOAD_SPEED_REPORTING_INTERVAL_IN_MILLISECONDS) {
                    delegate?.saveDownloadProgress(downloadInfo)
                }
            }
            reportingStopTime = System.nanoTime()
            val hasReportingTimeElapsed = hasIntervalTimeElapsed(reportingStartTime,
                    reportingStopTime, progressReportingIntervalMillis)
            if (hasReportingTimeElapsed) {
                if (progressReportingIntervalMillis <= DEFAULT_DOWNLOAD_SPEED_REPORTING_INTERVAL_IN_MILLISECONDS) {
                    delegate?.saveDownloadProgress(downloadInfo)
                }
                if (!terminated) {
                    delegate?.onProgress(
                            download = downloadInfo,
                            etaInMilliSeconds = estimatedTimeRemainingInMilliseconds,
                            downloadedBytesPerSecond = getAverageDownloadedBytesPerSecond())
                }
                reportingStartTime = System.nanoTime()
            }
            if (downloadSpeedCheckTimeElapsed) {
                downloadSpeedStartTime = System.nanoTime()
            }
        }
    }

    private fun mergeAllChunks(output: OutputStream?, randomAccessFile: RandomAccessFile?) {
        actionsCounter = 0
        queuedActionsTotal = 1
        phase = Phase.MERGING
        executorService?.execute({
            try {
                var interrupted = false
                for (fileChunk in fileChunks) {
                    if (!interrupted && !terminated) {
                        mergeChunk(fileChunk, output, randomAccessFile)
                    } else {
                        interrupted = true
                        break
                    }
                }
                if (interrupted) {
                    for (fileChuck in fileChunks) {
                        fileChuck.status = Status.QUEUED
                        fileChuck.errorException = null
                    }
                }
            } catch (e: Exception) {
                logger.e("FileDownloader", e)
            } finally {
                incrementActionCompleted()
            }
        })
    }

    private fun mergeChunk(fileChunk: FileChuck,
                           outputStream: OutputStream?,
                           randomAccessFile: RandomAccessFile?) {
        val chunkFile = getFile(fileChunk.file)
        val request = getRequestForDownload(downloadInfo, fileChunk.startBytes + fileChunk.downloaded)
        val inputStream = downloader.getRequestInputStream(request, 0)
        var inputRandomAccessFile: RandomAccessFile? = null
        if (inputStream == null) {
            inputRandomAccessFile = RandomAccessFile(chunkFile, "r")
        }
        try {
            val buffer = ByteArray(downloadBufferSizeBytes)
            var read = inputStream?.read(buffer, 0, downloadBufferSizeBytes)
                    ?: (inputRandomAccessFile?.read(buffer, 0, downloadBufferSizeBytes) ?: -1)
            while (read != -1 && !interrupted && !terminated) {
                outputStream?.write(buffer, 0, read)
                randomAccessFile?.write(buffer, 0, read)
                mergedBytesWritten += read
                read = inputStream?.read(buffer, 0, downloadBufferSizeBytes)
                        ?: (inputRandomAccessFile?.read(buffer, 0, downloadBufferSizeBytes) ?: -1)
            }
            fileChunk.status = Status.MERGED
            incrementMergeCompleted()
        } catch (e: Exception) {
            logger.e("FileDownloader", e)
            fileChunk.status = Status.ERROR
            fileChunk.errorException = e
            throw e
        } finally {
            try {
                inputStream?.close()
            } catch (e: Exception) {
                logger.e("FileDownloader", e)
            }
            try {
                inputRandomAccessFile?.close()
            } catch (e: Exception) {
                logger.e("FileDownloader", e)
            }
        }
    }

    private fun downloadChunks(chunksDownloadsList: List<FileChuck>) {
        actionsCounter = 0
        queuedActionsTotal = chunksDownloadsList.size
        phase = Phase.DOWNLOADING
        for (downloadChunk in chunksDownloadsList) {
            if (!interrupted && !terminated) {
                downloadChunk.status = Status.DOWNLOADING
                executorService?.execute({
                    val downloadRequest = getRequestForDownload(downloadInfo, downloadChunk.startBytes + downloadChunk.downloaded)
                    var downloadResponse: Downloader.Response? = null
                    var outputStream: OutputStream? = null
                    var randomAccessFileOutput: RandomAccessFile? = null
                    try {
                        downloadResponse = downloader.execute(downloadRequest)
                        if (!terminated && !interrupted && downloadResponse?.isSuccessful == true) {
                            val file = getFile(downloadChunk.file)
                            val seekPosition = if (downloadResponse.code == HttpURLConnection.HTTP_PARTIAL) {
                                downloadChunk.downloaded
                            } else {
                                0
                            }
                            outputStream = downloader.getRequestOutputStream(downloadRequest, seekPosition)
                            if (outputStream == null) {
                                randomAccessFileOutput = RandomAccessFile(file, "rw")
                                randomAccessFileOutput.seek(seekPosition)
                            }
                            var reportingStopTime: Long
                            val buffer = ByteArray(downloadBufferSizeBytes)
                            var read: Int = downloadResponse.byteStream?.read(buffer, 0, downloadBufferSizeBytes)
                                    ?: -1
                            var remainderBytes: Long = downloadChunk.endBytes - (downloadChunk.startBytes + downloadChunk.downloaded)
                            var reportingStartTime = System.nanoTime()
                            while (remainderBytes > 0L && read != -1 && !interrupted && !terminated) {
                                if (read <= remainderBytes) {
                                    randomAccessFileOutput?.write(buffer, 0, read)
                                    outputStream?.write(buffer, 0, read)
                                    downloadChunk.downloaded += read
                                    addToTotalDownloaded(read)
                                    read = downloadResponse.byteStream?.read(buffer, 0, downloadBufferSizeBytes) ?: -1
                                    remainderBytes = downloadChunk.endBytes - (downloadChunk.startBytes + downloadChunk.downloaded)
                                } else {
                                    randomAccessFileOutput?.write(buffer, 0, remainderBytes.toInt())
                                    outputStream?.write(buffer, 0, remainderBytes.toInt())
                                    downloadChunk.downloaded += remainderBytes
                                    addToTotalDownloaded(remainderBytes.toInt())
                                    read = -1
                                }
                                reportingStopTime = System.nanoTime()
                                val hasReportingTimeElapsed = hasIntervalTimeElapsed(reportingStartTime,
                                        reportingStopTime, DEFAULT_DOWNLOAD_SPEED_REPORTING_INTERVAL_IN_MILLISECONDS)
                                if (hasReportingTimeElapsed) {
                                    saveDownloadedInfoForFileChunk(downloadChunk, downloadChunk.downloaded)
                                    reportingStartTime = System.nanoTime()
                                }
                            }
                            if (remainderBytes == 0L) {
                                downloadChunk.status = Status.DOWNLOADED
                            } else {
                                downloadChunk.status = Status.QUEUED
                            }
                        } else if (downloadResponse == null && !interrupted && !terminated) {
                            throw FetchException(EMPTY_RESPONSE_BODY,
                                    FetchException.Code.EMPTY_RESPONSE_BODY)
                        } else if (downloadResponse?.isSuccessful == false && !interrupted && !terminated) {
                            throw FetchException(RESPONSE_NOT_SUCCESSFUL,
                                    FetchException.Code.REQUEST_NOT_SUCCESSFUL)
                        } else if (!interrupted && !terminated) {
                            throw FetchException(UNKNOWN_ERROR,
                                    FetchException.Code.UNKNOWN)
                        }
                    } catch (e: Exception) {
                        downloadChunk.status = Status.ERROR
                        downloadChunk.errorException = e
                    } finally {
                        try {
                            if (downloadResponse != null) {
                                downloader.disconnect(downloadResponse)
                            }
                        } catch (e: Exception) {
                            logger.e("FileDownloader", e)
                        }
                        try {
                            randomAccessFileOutput?.close()
                        } catch (e: Exception) {
                            logger.e("FileDownloader", e)
                        }
                        try {
                            outputStream?.close()
                        } catch (e: Exception) {
                            logger.e("FileDownloader", e)
                        }
                        incrementActionCompleted()
                    }
                })
            }
        }
    }

    private fun addToTotalDownloaded(read: Int) {
        synchronized(downloadedLock) {
            downloaded += read
        }
    }

    private fun incrementActionCompleted() {
        synchronized(actionCountLock) {
            actionsCounter += 1
            if (actionsCounter == queuedActionsTotal) {
                phase = Phase.IDLE
            }
        }
    }

    private fun incrementMergeCompleted() {
        synchronized(mergeCompletedCountLock) {
            mergeCompletedCount += 1
        }
    }

    private fun chunksMergingOrDownloading(): Boolean {
        return !interrupted && !terminated && (phase == Phase.DOWNLOADING || phase == Phase.MERGING)
    }

    data class FileChunkInfo(val chunkCount: Int, val bytesPerChunk: Long)

    data class FileChuck(val id: Int = 0,
                         val position: Int = 0,
                         val startBytes: Long = 0L,
                         val endBytes: Long = 0L,
                         @Volatile
                         var downloaded: Long = 0L,
                         var file: String,
                         @Volatile
                         var status: Status = Status.QUEUED,
                         var errorException: Throwable? = null) {

        val isDownloaded: Boolean
            get() {
                return status == Status.DOWNLOADED
            }
    }

    enum class Status {
        QUEUED,
        DOWNLOADING,
        DOWNLOADED,
        ERROR,
        MERGING,
        MERGED;
    }

    enum class Phase {
        DOWNLOADING,
        MERGING,
        COMPLETED,
        IDLE;
    }

}