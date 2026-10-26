package io.github.savvasg28.rampguard.adapters.webhook

import io.github.savvasg28.rampguard.core.model.ReleaseId
import io.github.savvasg28.rampguard.core.model.RolloutEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * The JSON document a webhook receiver gets, one per event. Field names and the `event` values are a contract for
 * receivers: add fields, never rename. Absent values are sent as `null` so every document has every key.
 *
 * `event` is one of `advanced`, `completed`, `halted`, `ready_to_publish`, `rejected`, `error`.
 */
@Serializable
data class WebhookEvent(
    val schema: Int = SCHEMA,
    val event: String,
    val app: String,
    val release: Release?,
    val from: Double?,
    val to: Double?,
    val reason: String?,
    val source: String?,
    val message: String?,
    val at: String,
    val run: String?,
) {
    @Serializable
    data class Release(
        val name: String,
        val versionCodes: List<Long>,
    )

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        const val SCHEMA: Int = 1
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
            val base =
                WebhookEvent(
                    event = "",
                    app = app,
                    release = null,
                    from = null,
                    to = null,
                    reason = null,
                    source = null,
                    message = null,
                    at = at.toString(),
                    run = run,
                )
            return when (event) {
                is RolloutEvent.Advanced ->
                    base.copy(
                        event = "advanced",
                        release = event.release.dto(),
                        from = event.fromFraction,
                        to = event.toFraction,
                    )
                is RolloutEvent.Completed ->
                    base.copy(event = "completed", release = event.release.dto(), from = event.fromFraction, to = 1.0)
                is RolloutEvent.Halted ->
                    base.copy(
                        event = "halted",
                        release = event.release.dto(),
                        reason = event.reason,
                        source = event.source,
                    )
                is RolloutEvent.ReadyToPublish -> base.copy(event = "ready_to_publish", release = event.release.dto())
                is RolloutEvent.Rejected -> base.copy(event = "rejected", release = event.release.dto())
                is RolloutEvent.GuardError -> base.copy(event = "error", message = event.message)
            }
        }

        private fun ReleaseId.dto() = Release(name, versionCodes)
    }
}
