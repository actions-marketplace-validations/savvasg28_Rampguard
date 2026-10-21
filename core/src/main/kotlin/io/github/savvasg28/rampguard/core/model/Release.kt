package io.github.savvasg28.rampguard.core.model

import kotlinx.serialization.Serializable

/**
 * `releaseLifecycleState` as reported by `applications.tracks.releases.list`.
 * `UNSPECIFIED` covers values this version of Rampguard does not know; they are ignored.
 */
@Serializable
enum class LifecycleState {
    UNSPECIFIED,
    DRAFT,
    NOT_SENT_FOR_REVIEW,
    IN_REVIEW,
    APPROVED_NOT_PUBLISHED,
    NOT_APPROVED,
    PUBLISHED,
}

/** Track release `status` as reported by `edits.tracks.get`. */
enum class RolloutStatus {
    UNSPECIFIED,
    DRAFT,
    IN_PROGRESS,
    HALTED,
    COMPLETED,
    ;

    /** A release a rollout autopilot can act on: it is live and not finished. */
    val isActiveRollout: Boolean get() = this == IN_PROGRESS || this == HALTED
}

/**
 * One release on the production track, merged from the lifecycle summary and the track detail.
 *
 * @property userFraction fraction of users (0..1) for a staged rollout; `null` when Play does not report one
 *   (completed releases, drafts, or when the track detail could not be read).
 */
data class ReleaseSnapshot(
    val release: ReleaseId,
    val lifecycle: LifecycleState,
    val status: RolloutStatus,
    val userFraction: Double?,
) {
    init {
        userFraction?.let { require(it in 0.0..1.0) { "userFraction must be within 0..1, was $it" } }
    }
}
