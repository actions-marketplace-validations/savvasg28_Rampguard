package io.github.savvasg28.rampguard.adapters.slack

import io.github.savvasg28.rampguard.core.model.RolloutEvent
import io.github.savvasg28.rampguard.core.model.percent
import io.github.savvasg28.rampguard.core.ports.Notifier
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Slack rejected or failed the post. The message never contains the webhook URL, which is a secret. */
class SlackException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Posts one message per event to a Slack incoming webhook. The webhook URL is a secret: it is held here, sent as the
 * request target and never logged, printed or put in an exception.
 */
class SlackNotifier(
    webhookUrl: String,
    private val app: String,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(DEFAULT_TIMEOUT).build(),
    private val timeout: Duration = DEFAULT_TIMEOUT,
) : Notifier {
    private val target: URI = URI.create(webhookUrl)

    override suspend fun notify(event: RolloutEvent) {
        val body = JsonObject(mapOf("text" to JsonPrimitive(SlackMessages.text(app, event)))).toString()
        val request =
            HttpRequest
                .newBuilder(target)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        val response =
            try {
                client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
            } catch (e: IOException) {
                throw SlackException("Slack webhook request failed: ${e::class.simpleName}", e)
            }
        if (response.statusCode() !in SUCCESS) {
            throw SlackException("Slack webhook returned ${response.statusCode()}: ${response.body().take(MAX_BODY)}")
        }
    }

    private companion object {
        val SUCCESS = 200..299
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(10)
        const val MAX_BODY = 200
    }
}

/** One line per event, readable in a channel. */
object SlackMessages {
    fun text(
        app: String,
        event: RolloutEvent,
    ): String =
        when (event) {
            is RolloutEvent.Advanced ->
                ":arrow_up: *$app* ${event.release.name}: ${percent(event.fromFraction)} → ${percent(event.toFraction)}"
            is RolloutEvent.Completed ->
                ":white_check_mark: *$app* ${event.release.name}: ${percent(
                    event.fromFraction,
                )} → 100%, rollout complete"
            is RolloutEvent.Halted -> ":octagonal_sign: *$app* ${event.release.name} halted. ${event.reason}"
            is RolloutEvent.ReadyToPublish ->
                ":hourglass: *$app* ${event.release.name} is approved and waiting to be published"
            is RolloutEvent.Rejected -> ":x: *$app* ${event.release.name} was not approved"
            is RolloutEvent.GuardError -> ":warning: *$app* rampguard: ${event.message}"
        }
}
