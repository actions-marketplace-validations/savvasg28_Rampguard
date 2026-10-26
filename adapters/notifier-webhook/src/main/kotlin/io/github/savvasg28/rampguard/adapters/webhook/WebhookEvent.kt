package io.github.savvasg28.rampguard.adapters.webhook

import io.github.savvasg28.rampguard.core.model.ReleaseId
import io.github.savvasg28.rampguard.core.model.RolloutEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.math.roundToLong

/**
 * The JSON document a webhook receiver gets, one per event. `type` names what happened; the object named after the
 * subject (`rollout`, `halt`, `failure`) carries the details, and is present only on the types that have it. Field
 * names and `type` values are a contract for receivers: add fields, never rename.
 *
 * | `type` | Present |
 * |---|---|
 * | `rollout.advanced` | `release`, `rollout.fromPercent`, `rollout.toPercent` |
 * | `rollout.completed` | `release`, `rollout.fromPercent`, `rollout.toPercent` (100) |
 * | `rollout.halted` | `release`, `halt.reason`, `halt.source` |
 * | `release.readyToPublish` | `release` |
 * | `release.rejected` | `release` |
 * | `run.failed` | `failure.message` |
 */
@Serializable
data class WebhookEvent(
    val type: String,
    val app: String,
    val occurredAt: String,
    val workflowRun: String? = null,
    val release: Release? = null,
    val rollout: Rollout? = null,
    val halt: Halt? = null,
    val failure: Failure? = null,
) {
    @Serializable
    data class Release(
        val name: String,
        val versionCodes: List<Long>,
    )

    /** Percentages as in the configuration's `stages`: `5` for a 5% rollout, not `0.05`. */
    @Serializable
    data class Rollout(
        val fromPercent: Double,
        val toPercent: Double,
    )

    @Serializable
    data class Halt(
        val reason: String,
        val source: String,
    )

    @Serializable
    data class Failure(
        val message: String,
    )

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { explicitNulls = false }
        private const val PERCENT = 100.0
        private const val CENTI = 100.0

        fun of(
            app: String,
            event: RolloutEvent,
            at: Instant,
            workflowRun: String?,
        ): WebhookEvent {
            fun document(
                type: String,
                release: ReleaseId? = null,
                rollout: Rollout? = null,
                halt: Halt? = null,
                failure: Failure? = null,
            ) = WebhookEvent(type, app, at.toString(), workflowRun, release?.dto(), rollout, halt, failure)

            return when (event) {
                is RolloutEvent.Advanced ->
                    document(
                        "rollout.advanced",
                        event.release,
                        Rollout(percent(event.fromFraction), percent(event.toFraction)),
                    )
                is RolloutEvent.Completed ->
                    document("rollout.completed", event.release, Rollout(percent(event.fromFraction), PERCENT))
                is RolloutEvent.Halted ->
                    document(
                        "rollout.halted",
                        event.release,
                        halt = Halt(event.reason, event.source),
                    )
                is RolloutEvent.ReadyToPublish -> document("release.readyToPublish", event.release)
                is RolloutEvent.Rejected -> document("release.rejected", event.release)
                is RolloutEvent.GuardError -> document("run.failed", failure = Failure(event.message))
            }
        }

        /** `0.2` becomes `20.0`. Two-decimal rounding stops binary fractions printing as `20.000000000000004`. */
        private fun percent(fraction: Double): Double = (fraction * PERCENT * CENTI).roundToLong() / CENTI

        private fun ReleaseId.dto() = Release(name, versionCodes)
    }
}
