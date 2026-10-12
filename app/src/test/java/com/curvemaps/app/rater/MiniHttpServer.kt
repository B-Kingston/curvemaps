package com.curvemaps.app.rater

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/*
 * Unit tests compile against android.jar, which has neither
 * com.sun.net.httpserver nor java.net.http, so the rater serves itself over
 * a plain ServerSocket: one request per connection, loopback only.
 */

internal class MiniRequest(val method: String, val path: String, val rawQuery: String?, val body: ByteArray)

internal class MiniResponse(
    val status: Int,
    val body: ByteArray,
    val type: String,
    val headers: Map<String, String> = emptyMap(),
)

internal class MiniHttpServer(private val port: Int, private val handler: (MiniRequest) -> MiniResponse) {
    private val pool = Executors.newFixedThreadPool(8)

    fun start() {
        val socket = ServerSocket(port, 50, InetAddress.getLoopbackAddress())
        Thread({
            while (true) {
                val client = socket.accept()
                pool.execute { serve(client) }
            }
        }, "rater-http").apply { isDaemon = true; start() }
    }

    private fun serve(client: Socket) {
        client.use { socket ->
            val input = BufferedInputStream(socket.getInputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            var length = 0
            while (true) {
                val header = readLine(input) ?: return
                if (header.isEmpty()) break
                val colon = header.indexOf(':')
                if (colon > 0 && header.substring(0, colon).trim().equals("Content-Length", ignoreCase = true)) {
                    length = header.substring(colon + 1).trim().toIntOrNull() ?: 0
                }
            }
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) break
                read += n
            }
            val target = parts[1]
            val query = target.indexOf('?')
            val request = MiniRequest(
                parts[0],
                if (query >= 0) target.substring(0, query) else target,
                if (query >= 0) target.substring(query + 1) else null,
                body,
            )
            val response = handler(request)
            val head = StringBuilder()
                .append("HTTP/1.1 ${response.status} ${reason(response.status)}\r\n")
                .append("Content-Type: ${response.type}\r\n")
                .append("Content-Length: ${response.body.size}\r\n")
                .append("Cache-Control: no-store\r\n")
                .append("Connection: close\r\n")
            response.headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
            head.append("\r\n")
            val out = socket.getOutputStream()
            out.write(head.toString().toByteArray())
            out.write(response.body)
            out.flush()
        }
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buffer.size() == 0) null else buffer.toString(Charsets.ISO_8859_1.name())
            if (b == '\n'.code) return buffer.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
            buffer.write(b)
            if (buffer.size() > 64 * 1024) return null
        }
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        404 -> "Not Found"
        409 -> "Conflict"
        else -> "Error"
    }
}
