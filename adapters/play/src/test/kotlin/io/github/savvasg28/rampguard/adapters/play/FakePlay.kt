package io.github.savvasg28.rampguard.adapters.play

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * A local stand-in for both Play APIs. Routes match on method, the end of the URL path and, optionally, a fragment of
 * the request body; each route plays its responses in order and repeats the last one. Unrouted requests get a 500 so
 * a test never passes by accident.
 */
class FakePlay : AutoCloseable {
    data class Call(
        val method: String,
        val path: String,
        val query: String?,
        val body: String,
        val authorization: String?,
    )

    private class Route(
        val method: String,
        val pathSuffix: String,
        val bodyContains: String?,
        val responses: List<MockResponse>,
    ) {
        var served = 0

        fun next(): MockResponse = responses[minOf(served++, responses.lastIndex)]
    }

    private val routes = CopyOnWriteArrayList<Route>()
    val calls: MutableList<Call> = CopyOnWriteArrayList()
    private val server = MockWebServer()

    init {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = checkNotNull(request.requestUrl) { "request without URL" }
                    val call =
                        Call(
                            method = checkNotNull(request.method),
                            path = url.encodedPath,
                            query = url.encodedQuery,
                            body = request.body.readUtf8(),
                            authorization = request.getHeader("Authorization"),
                        )
                    calls += call
                    val route =
                        routes.firstOrNull { r ->
                            r.method == call.method &&
                                call.path.endsWith(r.pathSuffix) &&
                                (r.bodyContains == null || call.body.contains(r.bodyContains))
                        }
                            ?: return json(
                                500,
                                """{"error":{"code":500,"message":"unrouted ${call.method} ${call.path}"}}""",
                            )
                    return route.next()
                }
            }
        server.start()
    }

    val endpoints: PlayEndpoints
        get() =
            PlayEndpoints(
                publisher = server.url("/androidpublisher/v3").toString(),
                reporting = server.url("/v1beta1").toString(),
            )

    fun http(timeout: Duration = Duration.ofSeconds(5)): PlayHttp =
        PlayHttp(tokens = { TOKEN }, requestTimeout = timeout)

    fun on(
        method: String,
        pathSuffix: String,
        vararg responses: MockResponse,
        bodyContains: String? = null,
    ) {
        require(responses.isNotEmpty())
        routes += Route(method, pathSuffix, bodyContains, responses.toList())
    }

    fun count(
        method: String,
        pathSuffix: String,
    ): Int = calls.count { it.method == method && it.path.endsWith(pathSuffix) }

    fun bodies(
        method: String,
        pathSuffix: String,
    ): List<String> = calls.filter { it.method == method && it.path.endsWith(pathSuffix) }.map { it.body }

    override fun close() = server.shutdown()

    companion object {
        const val TOKEN = "test-token"

        fun json(
            code: Int,
            body: String,
        ): MockResponse =
            MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

        fun ok(body: String): MockResponse = json(200, body)

        fun noContent(): MockResponse = MockResponse().setResponseCode(204)

        fun tooManyRequests(): MockResponse =
            json(429, """{"error":{"code":429,"message":"Quota exceeded","status":"RESOURCE_EXHAUSTED"}}""")

        fun slow(delay: Duration): MockResponse = ok("{}").setHeadersDelay(delay.toMillis(), TimeUnit.MILLISECONDS)

        fun fixture(path: String): String =
            checkNotNull(FakePlay::class.java.getResource("/contract/$path")) { "missing fixture $path" }.readText()
    }
}
