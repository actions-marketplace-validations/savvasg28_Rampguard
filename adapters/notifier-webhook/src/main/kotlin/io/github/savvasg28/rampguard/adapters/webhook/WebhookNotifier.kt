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

/** The receiver rejected or failed the post. The message never contains the URL, the headers or the secret. */
class WebhookException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Posts one [WebhookEvent] per event to a URL, with optional extra headers and an HMAC-SHA256 signature of the body in
 * `X-Rampguard-Signature` (`sha256=<hex>`). URL, headers and secret are held here and never logged or put in an
 * exception, since any of them may be a credential.
 */
class WebhookNotifier(
    url: String,
    private val app: String,
    private val clock: Clock,
    private val headers: Map<String, String> = emptyMap(),
    secret: String? = null,
    private val runUrl: String? = null,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(DEFAULT_TIMEOUT).build(),
    private val timeout: Duration = DEFAULT_TIMEOUT,
) : Notifier {
    private val target: URI = URI.create(url)
    private val key: SecretKeySpec? = secret?.takeIf { it.isNotEmpty() }?.let { SecretKeySpec(it.toByteArray(), HMAC) }

    override suspend fun notify(event: RolloutEvent) {
        val body = WebhookEvent.of(app, event, clock.now(), runUrl).toJson()
        val request =
            HttpRequest
                .newBuilder(target)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("User-Agent", "rampguard")
                .apply {
                    headers.forEach { (name, value) -> header(name, value) }
                    key?.let { header(SIGNATURE_HEADER, "sha256=${sign(it, body)}") }
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
        const val SIGNATURE_HEADER: String = "X-Rampguard-Signature"
        private const val HMAC = "HmacSHA256"
        private const val MAX_BODY = 200
        private val SUCCESS = 200..299
        private val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(10)

        fun sign(
            key: SecretKeySpec,
            body: String,
        ): String =
            Mac
                .getInstance(HMAC)
                .apply { init(key) }
                .doFinal(body.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
