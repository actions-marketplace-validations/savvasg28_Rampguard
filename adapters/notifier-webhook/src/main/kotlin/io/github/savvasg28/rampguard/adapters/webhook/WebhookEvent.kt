package io.github.savvasg28.rampguard.adapters.webhook

import io.github.savvasg28.rampguard.core.model.ReleaseId
import io.github.savvasg28.rampguard.core.model.RolloutEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * The JSON document a webhook receiver gets, one per event. Field names and the `event` values are a contract for
 * receivers: add fields, never rename. Every document has every key; a value that does not apply is `null`.
 *
 * `event` is one of `advanced`, `completed`, `halted`, `ready_to_publish`, `rejected`, `error`.
 */
@Serializable
data class WebhookEvent(
    val event: String,
    val app: String,
    val at: String,
    val run: String?,
    val release: Release? = null,
    val from: Double? = null,
    val to: Double? = null,
    val reason: String? = null,
    val source: String? = null,
    val message: String? = null,
) {
    @Serializable
    data class Release(
        val name: String,
        val versionCodes: List<Long>,
    )

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json =
            Json {
                encodeDefaults = true
                explicitNulls = true
            }

        fun of(
            app: String,
            event: RolloutEvent,
            at: Instant,
            run: String?,
        ): WebhookEvent {
            val time = at.toString()
            return when (event) {
                is RolloutEvent.Advanced ->
                    WebhookEvent(
                        "advanced",
                        app,
                        time,
                        run,
                        event.release.dto(),
                        from = event.fromFraction,
                        to = event.toFraction,
                    )
                is RolloutEvent.Completed ->
                    WebhookEvent("completed", app, time, run, event.release.dto(), from = event.fromFraction, to = 1.0)
                is RolloutEvent.Halted ->
                    WebhookEvent(
                        "halted",
                        app,
                        time,
                        run,
                        event.release.dto(),
                        reason = event.reason,
                        source = event.source,
                    )
                is RolloutEvent.ReadyToPublish -> WebhookEvent("ready_to_publish", app, time, run, event.release.dto())
                is RolloutEvent.Rejected -> WebhookEvent("rejected", app, time, run, event.release.dto())
                is RolloutEvent.GuardError -> WebhookEvent("error", app, time, run, message = event.message)
            }
        }

        private fun ReleaseId.dto() = Release(name, versionCodes)
    }
}
