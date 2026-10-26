package io.github.savvasg28.rampguard.adapters.webhook

import io.github.savvasg28.rampguard.core.model.RolloutEvent
import io.github.savvasg28.rampguard.core.ports.Clock
import io.github.savvasg28.rampguard.core.ports.Notifier
import kotlinx.coroutines.future.await
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Where events go. All three parts may be credentials, so the class prints none of them.
 *
 * @property url the receiver
 * @property headers extra request headers, already resolved to their values
 * @property secret when set, each request carries `X-Rampguard-Signature: sha256=<hex HMAC-SHA256 of the body>`
 */
class WebhookTarget(
    url: String,
    val headers: Map<String, String> = emptyMap(),
    secret: String? = null,
) {
    val uri: URI = URI.create(url)
    val key: SecretKeySpec? = secret?.takeIf { it.isNotEmpty() }?.let { SecretKeySpec(it.toByteArray(), HMAC) }

    override fun toString(): String = "WebhookTarget(host=${uri.host})"

    companion object {
        const val SIGNATURE_HEADER: String = "X-Rampguard-Signature"
        private const val HMAC = "HmacSHA256"

        fun signature(
            key: SecretKeySpec,
            body: String,
        ): String =
            "sha256=" +
                Mac
                    .getInstance(HMAC)
                    .apply { init(key) }
                    .doFinal(body.toByteArray())
                    .joinToString("") { "%02x".format(it) }
    }
}

/** The receiver rejected or failed the post. The message never contains the URL, the headers or the secret. */
class WebhookException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** Posts one [WebhookEvent] per event to a [WebhookTarget]. */
class WebhookNotifier(
    private val target: WebhookTarget,
    private val app: String,
    private val runUrl: String?,
    private val clock: Clock,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(DEFAULT_TIMEOUT).build(),
    private val timeout: Duration = DEFAULT_TIMEOUT,
) : Notifier {
    override suspend fun notify(event: RolloutEvent) {
        val body = WebhookEvent.of(app, event, clock.now(), runUrl).toJson()
        val request =
            HttpRequest
                .newBuilder(target.uri)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("User-Agent", "rampguard")
                .apply {
                    target.headers.forEach { (name, value) -> header(name, value) }
                    target.key?.let { header(WebhookTarget.SIGNATURE_HEADER, WebhookTarget.signature(it, body)) }
                }.POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        val response =
            try {
                client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
            } catch (e: IOException) {
                throw WebhookException("webhook request failed: ${e::class.simpleName}", e)
            }
        if (response.statusCode() !in SUCCESS) {
            throw WebhookException("webhook returned ${response.statusCode()}: ${response.body().take(MAX_BODY)}")
        }
    }

    companion object {
        private const val MAX_BODY = 200
        private val SUCCESS = 200..299
        private val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}
