package com.noapmat.tsream.streaming

import com.noapmat.tsream.torrent.FileStream
import com.noapmat.tsream.torrent.HttpRange
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Tiny HTTP server on 127.0.0.1 that serves one torrent file with Range support, so any player that can
 * open an http:// URL (libVLC here) can stream it. A request blocks until the pieces it needs are verified.
 * The newest connection wins: players re-open the stream with a new Range after every seek, so older
 * handlers (possibly still waiting for a piece at the old position) are interrupted.
 */
class StreamServer(private val stream: FileStream, private val mime: String) {
    private val token = UUID.randomUUID().toString().replace("-", "")
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "tstream-http").apply { isDaemon = true } }
    private val active = ArrayList<Pair<Socket, Thread>>()
    @Volatile private var closed = false

    val url: String get() = "http://127.0.0.1:${server.localPort}/$token"

    fun start() {
        pool.execute {
            while (!closed) {
                val s = try { server.accept() } catch (e: IOException) { break }
                pool.execute { handle(s) }
            }
        }
    }

    fun stop() {
        closed = true
        try { server.close() } catch (e: IOException) {}
        synchronized(active) { active.forEach { (s, t) -> try { s.close() } catch (e: IOException) {}; t.interrupt() }; active.clear() }
        pool.shutdownNow()
    }

    private fun handle(s: Socket) {
        val me = Thread.currentThread()
        synchronized(active) {
            // newest wins
            active.forEach { (old, t) -> try { old.close() } catch (e: IOException) {}; t.interrupt() }
            active.clear()
            active.add(s to me)
        }
        try {
            s.use { serve(it) }
        } catch (e: Exception) {
            // client went away / interrupted: nothing to report
        } finally {
            synchronized(active) { active.removeAll { it.first === s } }
        }
    }

    private fun serve(s: Socket) {
        val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = reader.readLine() ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        var rangeHeader: String? = null
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Range:", ignoreCase = true)) rangeHeader = line.substringAfter(':').trim()
        }
        val out = s.getOutputStream()
        if (parts[1].substringBefore('?') != "/$token") {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            return
        }
        val total = stream.size
        val range = HttpRange.parse(rangeHeader, total)
        if (range == null) {
            out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            return
        }
        val partial = rangeHeader != null && rangeHeader.startsWith("bytes=", ignoreCase = true)
        val length = range.last - range.first + 1
        val head = StringBuilder()
            .append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            .append("Content-Type: $mime\r\nAccept-Ranges: bytes\r\nContent-Length: $length\r\n")
        if (partial) head.append("Content-Range: bytes ${range.first}-${range.last}/$total\r\n")
        head.append("Connection: close\r\n\r\n")
        out.write(head.toString().toByteArray())
        if (parts[0].equals("HEAD", ignoreCase = true)) return

        val buf = ByteArray(64 * 1024)
        var raf: RandomAccessFile? = null
        try {
            var pos = range.first
            var remaining = length
            while (remaining > 0 && !closed) {
                val n = stream.awaitAvailable(pos, minOf(buf.size.toLong(), remaining).toInt())
                if (raf == null) raf = RandomAccessFile(stream.file, "r") // exists once the first piece is on disk
                raf.seek(pos)
                val r = raf.read(buf, 0, n)
                if (r <= 0) break
                out.write(buf, 0, r)
                pos += r
                remaining -= r
            }
            out.flush()
        } finally {
            raf?.close()
        }
    }
}
