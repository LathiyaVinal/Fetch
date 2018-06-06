package com.tonyodev.fetch2fileserver

import com.tonyodev.fetch2.Downloader
import com.tonyodev.fetch2.FileServerDownloader
import com.tonyodev.fetch2.util.*
import com.tonyodev.fetch2fileserver.FileRequest.Companion.TYPE_FILE
import com.tonyodev.fetch2fileserver.transporter.FetchContentFileTransporter

import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.util.*

/**
 * This downloader is used by Fetch to download files from a Fetch File Server using the
 * Fetch file server url scheme.
 * @see {@link com.tonyodev.fetch2.Downloader}
 * */
open class FetchFileServerDownloader @JvmOverloads constructor(

        /** The file downloader type used to download a request.
         * The SEQUENTIAL type downloads bytes in sequence.
         * The PARALLEL type downloads bytes in parallel.
         * */
        private val fileDownloaderType: Downloader.FileDownloaderType = Downloader.FileDownloaderType.SEQUENTIAL,

        /** The timeout value in milliseconds when trying to connect to the server. Default is 20_000 milliseconds. */
        private val timeout: Long = 20_000) : FileServerDownloader {

    protected val connections: MutableMap<Downloader.Response, FetchContentFileTransporter> = Collections.synchronizedMap(HashMap<Downloader.Response, FetchContentFileTransporter>())

    override fun execute(request: Downloader.ServerRequest, interruptMonitor: InterruptMonitor?): Downloader.Response? {
        val headers = request.headers
        val range = getRangeForFetchFileServerRequest(headers["Range"] ?: "bytes=0-")
        val authorization = headers[FileRequest.FIELD_AUTHORIZATION] ?: ""
        val port = getFetchFileServerPort(request.url)
        val address = getFetchFileServerHostAddress(request.url)
        val inetSocketAddress = InetSocketAddress(address, port)
        val transporter = FetchContentFileTransporter()
        var timeoutStop: Long
        val timeoutStart = System.nanoTime()
        transporter.connect(inetSocketAddress)
        val contentFileRequest = FileRequest(
                type = TYPE_FILE,
                contentFileId = getContentFileIdFromUrl(request.url),
                rangeStart = range.first,
                rangeEnd = range.second,
                authorization = authorization,
                client = headers[FileRequest.FIELD_CLIENT] ?: UUID.randomUUID().toString(),
                customData = headers[FileRequest.FIELD_CUSTOM_DATA] ?: "",
                page = headers[FileRequest.FIELD_PAGE]?.toIntOrNull() ?: 0,
                size = headers[FileRequest.FIELD_SIZE]?.toIntOrNull() ?: 0,
                persistConnection = false)
        transporter.sendContentFileRequest(contentFileRequest)
        while (interruptMonitor?.isInterrupted == false) {
            val serverResponse = transporter.receiveContentFileResponse()
            if (serverResponse != null) {
                val code = serverResponse.status
                val isSuccessful = serverResponse.connection == FileResponse.OPEN_CONNECTION &&
                        serverResponse.type == FileRequest.TYPE_FILE && serverResponse.status == HttpURLConnection.HTTP_PARTIAL
                val contentLength = serverResponse.contentLength
                val inputStream = transporter.getInputStream()
                val response = Downloader.Response(
                        code = code,
                        isSuccessful = isSuccessful,
                        contentLength = contentLength,
                        byteStream = inputStream,
                        request = request,
                        md5 = serverResponse.md5)
                connections[response] = transporter
                return response
            }
            timeoutStop = System.nanoTime()
            if (hasIntervalTimeElapsed(timeoutStart, timeoutStop, timeout)) {
                return null
            }
        }
        return null
    }

    override fun disconnect(response: Downloader.Response) {
        if (connections.contains(response)) {
            val transporter = connections[response] as FetchContentFileTransporter
            connections.remove(response)
            transporter.close()
        }
    }

    override fun close() {
        try {
            connections.entries.forEach {
                it.value.close()
            }
            connections.clear()
        } catch (e: Exception) {

        }
    }

    override fun getRequestOutputStream(request: Downloader.ServerRequest, filePointerOffset: Long): OutputStream? {
        return null
    }

    override fun seekOutputStreamToPosition(request: Downloader.ServerRequest, outputStream: OutputStream, filePointerOffset: Long) {

    }

    override fun getFileSlicingCount(request: Downloader.ServerRequest, contentLength: Long): Int? {
        return null
    }

    override fun getFileDownloaderType(request: Downloader.ServerRequest): Downloader.FileDownloaderType {
        return fileDownloaderType
    }

    override fun getDirectoryForFileDownloaderTypeParallel(request: Downloader.ServerRequest): String? {
        return null
    }

    override fun verifyContentMD5(request: Downloader.ServerRequest, md5: String): Boolean {
        if (md5.isEmpty()) {
            return true
        }
        val fileMd5 = getFileMd5String(request.file)
        return fileMd5?.contentEquals(md5) ?: true
    }

}