package io.github.savvasg28.rampguard.adapters.play.publisher

import io.github.savvasg28.rampguard.core.model.ReleaseId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Thrown when the release is not in a state the requested write applies to. Never retried. */
class UnexpectedReleaseStateException(
    message: String,
) : IllegalStateException(message)

/**
 * One write to a production release, applied to the raw track JSON read inside the edit. Only `status` and
 * `userFraction` of the matching release change; every other field of every release is sent back as read.
 */
internal sealed interface TrackEdit {
    val description: String

    /** True when the release already looks the way this write would leave it, e.g. after a concurrent commit. */
    fun alreadyApplied(release: JsonObject): Boolean

    /** The release after the write, or [UnexpectedReleaseStateException] when the write does not apply. */
    fun apply(release: JsonObject): JsonObject

    data class SetFraction(
        val fraction: Double,
    ) : TrackEdit {
        init {
            require(fraction > 0.0 && fraction < 1.0) { "a staged fraction must be within (0, 1), was $fraction" }
        }

        override val description: String get() = "set rollout to $fraction"

        /** Never lowers a rollout: a fraction at or above the target, or a completed release, counts as done. */
        override fun alreadyApplied(release: JsonObject): Boolean =
            release.status() == COMPLETED ||
                (release.status() == IN_PROGRESS && (release.fraction() ?: 0.0) >= fraction - EPSILON)

        override fun apply(release: JsonObject): JsonObject {
            requireStatus(release, IN_PROGRESS, description)
            return release.with("userFraction", JsonPrimitive(fraction))
        }
    }

    data object Halt : TrackEdit {
        override val description: String = "halt"

        override fun alreadyApplied(release: JsonObject): Boolean = release.status() == HALTED

        override fun apply(release: JsonObject): JsonObject {
            requireStatus(release, IN_PROGRESS, description)
            return release.with("status", JsonPrimitive(HALTED))
        }
    }

    data object Complete : TrackEdit {
        override val description: String = "complete"

        override fun alreadyApplied(release: JsonObject): Boolean = release.status() == COMPLETED

        override fun apply(release: JsonObject): JsonObject {
            requireStatus(release, IN_PROGRESS, description)
            return JsonObject(release - "userFraction").with("status", JsonPrimitive(COMPLETED))
        }
    }

    companion object {
        const val IN_PROGRESS = "inProgress"
        const val HALTED = "halted"
        const val COMPLETED = "completed"
        private const val EPSILON = 1e-9

        private fun requireStatus(
            release: JsonObject,
            expected: String,
            what: String,
        ) {
            val actual = release.status()
            if (actual != expected) {
                throw UnexpectedReleaseStateException("cannot $what: release status is $actual, expected $expected")
            }
        }
    }
}

internal fun JsonObject.status(): String? = this["status"]?.jsonPrimitive?.contentOrNull

internal fun JsonObject.fraction(): Double? = this["userFraction"]?.jsonPrimitive?.doubleOrNull

internal fun JsonObject.versionCodeSet(): Set<Long> =
    (this["versionCodes"] as? JsonArray)
        ?.mapNotNull { it.jsonPrimitive.contentOrNull?.toLongOrNull() }
        ?.toSet()
        .orEmpty()

private fun JsonObject.with(
    key: String,
    value: JsonElement,
): JsonObject = JsonObject(this + (key to value))

/** The release in [track] whose version codes equal [release]'s, as raw JSON. */
internal fun findRelease(
    track: JsonObject,
    release: ReleaseId,
): JsonObject? =
    track["releases"]
        ?.jsonArray
        ?.map { it.jsonObject }
        ?.firstOrNull { it.versionCodeSet() == release.versionCodes.toSet() }

/** [track] with [release] replaced by [replacement]; all other releases and track fields unchanged. */
internal fun replaceRelease(
    track: JsonObject,
    release: ReleaseId,
    replacement: JsonObject,
): JsonObject {
    val releases =
        track["releases"]?.jsonArray.orEmpty().map { element ->
            val obj = element.jsonObject
            if (obj.versionCodeSet() == release.versionCodes.toSet()) replacement else obj
        }
    return JsonObject(track + ("releases" to JsonArray(releases)))
}
