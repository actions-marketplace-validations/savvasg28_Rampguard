package io.github.savvasg28.rampguard.core.model

/**
 * What one run saw on the production track. Built by the runner, consumed by the pure engine.
 *
 * @property production every release currently on the production track
 * @property playError when non-null, the track could not be read and [production] is meaningless
 */
data class Observation(
    val production: List<ReleaseSnapshot>,
    val playError: String? = null,
) {
    /** The rollout the autopilot would act on: the single in-progress or halted release, if any. */
    val activeRollout: ReleaseSnapshot? get() = production.firstOrNull { it.status.isActiveRollout }

    /** The newest completed release on the track, if any. */
    val latestCompleted: ReleaseSnapshot?
        get() = production.filter { it.status == RolloutStatus.COMPLETED }.maxByOrNull { it.release.maxVersionCode }

    companion object {
        fun playUnreadable(message: String): Observation = Observation(production = emptyList(), playError = message)
    }
}
