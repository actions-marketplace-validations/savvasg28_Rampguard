package io.github.savvasg28.rampguard.adapters.play

import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

/** Supplies an OAuth access token for the Play APIs. Implementations must never log the token. */
fun interface AccessTokenProvider {
    suspend fun token(): String
}

/** Base URLs of the two Play APIs; tests point them at a local server. */
data class PlayEndpoints(
    val publisher: String = "https://androidpublisher.googleapis.com/androidpublisher/v3",
    val reporting: String = "https://playdeveloperreporting.googleapis.com/v1beta1",
)

/**
 * How reads back off on `429 Too Many Requests`. Writes never go through this; they have their own conflict loop.
 * `Retry-After` is honoured up to [maxDelay].
 */
data class RetryPolicy(
    val maxAttempts: Int = DEFAULT_READ_ATTEMPTS,
    val initialDelay: Duration = DEFAULT_INITIAL_BACKOFF,
    val maxDelay: Duration = DEFAULT_MAX_BACKOFF,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
    }
}

/** Play answered with a non-2xx status. [apiStatus] is the `error.status` string, e.g. `PERMISSION_DENIED`. */
class PlayApiException(
    val httpStatus: Int,
    val apiStatus: String?,
    detail: String?,
    val retryAfter: Duration? = null,
) : IOException("Play API returned $httpStatus${apiStatus?.let { " $it" } ?: ""}${detail?.let { ": $it" } ?: ""}")

/** The request did not complete: timeout, connection reset, DNS. Whether a write took effect is unknown. */
class PlayTransportException(
    message: String,
    cause: Throwable,
    val timedOut: Boolean,
) : IOException(message, cause)

/** Play answered 2xx but the body is not the JSON we expect. */
class MalformedResponseException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Thin JSON-over-HTTPS client for the Play APIs. Every request carries [requestTimeout]; errors become the exceptions
 * above, whose messages never include the token or request headers.
 */
class PlayHttp(
    private val tokens: AccessTokenProvider,
    private val requestTimeout: Duration = DEFAULT_REQUEST_TIMEOUT,
    private val client: HttpClient = defaultClient(),
) {
    suspend fun get(url: String): JsonElement? = send("GET", url, null)

    suspend fun post(
        url: String,
        body: JsonElement?,
    ): JsonElement? = send("POST", url, body)

    suspend fun put(
        url: String,
        body: JsonElement,
    ): JsonElement? = send("PUT", url, body)

    suspend fun delete(url: String): JsonElement? = send("DELETE", url, null)

    private suspend fun send(
        method: String,
        url: String,
        body: JsonElement?,
    ): JsonElement? {
        val publisher =
            body?.let { HttpRequest.BodyPublishers.ofString(it.toString()) } ?: HttpRequest.BodyPublishers.noBody()
        val request =
            HttpRequest
                .newBuilder(URI.create(url))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer ${tokens.token()}")
                .header("Accept", "application/json")
                .apply { if (body != null) header("Content-Type", "application/json") }
                .method(method, publisher)
                .build()
        val response = exchange(request)
        val text = response.body().orEmpty()
        if (response.statusCode() !in SUCCESS) throw apiError(response.statusCode(), text, response)
        if (text.isBlank()) return null
        return try {
            JSON.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw MalformedResponseException("$method ${path(url)} returned a body that is not JSON", e)
        }
    }

    private suspend fun exchange(request: HttpRequest): HttpResponse<String> {
        val what = "${request.method()} ${request.uri().path}"
        return try {
            client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        } catch (e: HttpTimeoutException) {
            throw PlayTransportException("$what timed out after $requestTimeout", e, timedOut = true)
        } catch (e: IOException) {
            throw PlayTransportException("$what failed: ${e.message}", e, timedOut = false)
        }
    }

    private fun apiError(
        status: Int,
        text: String,
        response: HttpResponse<String>,
    ): PlayApiException {
        val error =
            runCatching { JSON.parseToJsonElement(text).jsonObject["error"]?.jsonObject }.getOrNull() ?: JsonObject(
                emptyMap(),
            )
        val retryAfter =
            response
                .headers()
                .firstValue("Retry-After")
                .map { it.toLongOrNull()?.let(Duration::ofSeconds) }
                .orElse(null)
        return PlayApiException(
            httpStatus = status,
            apiStatus = error["status"]?.jsonPrimitive?.contentOrNull,
            detail = error["message"]?.jsonPrimitive?.contentOrNull,
            retryAfter = retryAfter,
        )
    }

    /** The URL path only: query strings and hosts stay out of messages. */
    private fun path(url: String): String = URI.create(url).path

    companion object {
        private val SUCCESS = 200..299

        val JSON: Json =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }

        fun defaultClient(): HttpClient =
            HttpClient
                .newBuilder()
                .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
    }
}

/** Runs a read, retrying on 429 with exponential backoff (or `Retry-After`). Any other failure propagates at once. */
internal suspend fun <T> RetryPolicy.retryOnTooManyRequests(block: suspend () -> T): T {
    var wait = initialDelay
    repeat(maxAttempts - 1) {
        try {
            return block()
        } catch (e: PlayApiException) {
            if (e.httpStatus != TOO_MANY_REQUESTS) throw e
            delay(minOf(e.retryAfter ?: wait, maxDelay).toMillis())
            wait = minOf(wait.multipliedBy(2), maxDelay)
        }
    }
    return block()
}

internal const val TOO_MANY_REQUESTS = 429
private const val DEFAULT_READ_ATTEMPTS = 4
private val DEFAULT_INITIAL_BACKOFF: Duration = Duration.ofSeconds(2)
private val DEFAULT_MAX_BACKOFF: Duration = Duration.ofSeconds(30)
private val DEFAULT_REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)
private val DEFAULT_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)

/** Decodes a response body into [T]; an empty body or an unexpected shape is a [MalformedResponseException]. */
internal inline fun <reified T> decodeBody(
    body: JsonElement?,
    what: String,
): T {
    val element = body ?: throw MalformedResponseException("$what returned an empty body")
    return try {
        PlayHttp.JSON.decodeFromJsonElement<T>(element)
    } catch (e: IllegalArgumentException) {
        // Also covers SerializationException, which extends it.
        throw MalformedResponseException("$what has an unexpected shape: ${e.message}", e)
    }
}
