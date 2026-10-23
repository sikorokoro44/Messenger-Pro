package com.sikorokoro44.messengerpro.remote

class FakeTransport : RemoteTransport {
    data class Call(val method: String, val url: String, val token: String?, val body: String?)

    val calls = mutableListOf<Call>()
    private val responses = ArrayDeque<RemoteResponse>()

    fun enqueue(status: Int, body: String) {
        responses.addLast(RemoteResponse(status, body))
    }

    override fun request(method: String, url: String, token: String?, body: String?): RemoteResponse {
        calls += Call(method, url, token, body)
        return responses.removeFirstOrNull() ?: RemoteResponse(500, """{"error":{"code":"no_response","message":"No fake response"}}""")
    }
}
