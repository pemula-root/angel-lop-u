package com.example.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Membungkus data source HTTP agar membaca URL stream dalam potongan berbatas (range tertutup).
 *
 * Sebagian URL stream (mis. dari klien ANDROID_VR) menolak permintaan terbuka seperti
 * `Range: bytes=0-` dengan HTTP 403 dan hanya melayani range berbatas sekitar 1 MiB.
 * Pemutar melihat satu aliran utuh, sedangkan permintaan ke server dipecah per [chunkSize].
 * Panjang total diambil dari parameter `clen` pada URL; bila tidak ada, permintaan diteruskan apa adanya.
 */
class ChunkedDataSource(
    private val upstream: DataSource,
    private val chunkSize: Long = 512L * 1024L
) : DataSource {

    private var baseSpec: DataSpec? = null
    private var passthrough = false
    private var position = 0L
    private var remaining = 0L
    private var chunkRemaining = 0L
    private var upstreamOpen = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        baseSpec = dataSpec
        val total = dataSpec.uri.getQueryParameter("clen")?.toLongOrNull()
        val unset = C.LENGTH_UNSET.toLong()

        passthrough = total == null && dataSpec.length == unset
        if (passthrough) {
            val length = upstream.open(dataSpec)
            upstreamOpen = true
            return length
        }

        position = dataSpec.position
        remaining = if (dataSpec.length != unset) {
            dataSpec.length
        } else {
            (total!! - dataSpec.position).coerceAtLeast(0L)
        }
        chunkRemaining = 0L
        if (remaining > 0L) openNextChunk()
        return remaining
    }

    private fun openNextChunk() {
        val spec = baseSpec ?: return
        val length = minOf(chunkSize, remaining)
        val chunkSpec = spec.buildUpon()
            .setPosition(position)
            .setLength(length)
            .build()
        upstream.open(chunkSpec)
        upstreamOpen = true
        chunkRemaining = length
    }

    private fun closeUpstream() {
        if (upstreamOpen) {
            try {
                upstream.close()
            } finally {
                upstreamOpen = false
            }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (passthrough) return upstream.read(buffer, offset, length)
        if (remaining <= 0L) return C.RESULT_END_OF_INPUT

        if (chunkRemaining <= 0L) {
            closeUpstream()
            openNextChunk()
        }

        val toRead = minOf(length.toLong(), chunkRemaining).toInt()
        val read = upstream.read(buffer, offset, toRead)
        if (read == C.RESULT_END_OF_INPUT) return C.RESULT_END_OF_INPUT

        position += read
        remaining -= read
        chunkRemaining -= read
        return read
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        closeUpstream()
        baseSpec = null
    }
}
