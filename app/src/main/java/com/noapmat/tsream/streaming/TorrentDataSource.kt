package com.noapmat.tsream.streaming

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.noapmat.tsream.torrent.FileStream
import java.io.EOFException
import java.io.RandomAccessFile

/** Media3 -> torrent bridge: random-access reads that block until the needed piece is verified. */
@OptIn(UnstableApi::class)
class TorrentDataSource(private val stream: FileStream) : BaseDataSource(/* isNetwork = */ true) {
    private var uri: Uri? = null
    private var raf: RandomAccessFile? = null
    private var pos = 0L
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        pos = dataSpec.position
        if (pos > stream.size) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        val left = stream.size - pos
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) left else minOf(dataSpec.length, left)
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val n = stream.awaitAvailable(pos, minOf(length.toLong(), remaining).toInt())
        val f = raf ?: RandomAccessFile(stream.file, "r").also { raf = it }
        f.seek(pos)
        val r = f.read(buffer, offset, n)
        if (r <= 0) throw EOFException("Unexpected end of cached file")
        pos += r
        remaining -= r
        bytesTransferred(r)
        return r
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        raf?.close(); raf = null
        if (opened) { opened = false; transferEnded() }
    }
}
