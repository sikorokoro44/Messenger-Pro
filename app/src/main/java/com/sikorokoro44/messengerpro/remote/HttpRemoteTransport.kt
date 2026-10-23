package com.sikorokoro44.messengerpro.remote

import java.net.HttpURLConnection
import java.net.URL

class HttpRemoteTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 20_000
) : RemoteTransport {

    override fun request(method: String, url: String, token: String?, body: String?): RemoteResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            useCaches = false
            setRequestProperty("Accept", "application/json")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        return try {
            if (body != null) {
                connection.outputStream.use { stream -> stream.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            RemoteResponse(status, text)
        } finally {
            connection.disconnect()
        }
    }
}
