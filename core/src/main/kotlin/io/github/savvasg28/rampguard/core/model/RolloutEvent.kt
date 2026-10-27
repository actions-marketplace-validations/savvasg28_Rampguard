package io.github.savvasg28.rampguard.core.model

/**
 * Something a run saw or did that the workflow may want to act on. There is no memory between runs, so a state that
 * persists (a release waiting to be published) is reported on every run that sees it.
 */
sealed interface RolloutEvent {
    data class ReadyToPublish(
        val release: ReleaseId,
    ) : RolloutEvent {
        override fun toString(): String = "ready-to-publish:$release"
    }

    data class Rejected(
        val release: ReleaseId,
    ) : RolloutEvent {
        override fun toString(): String = "rejected:$release"
    }

    data class Advanced(
        val release: ReleaseId,
        val fromFraction: Double,
        val toFraction: Double,
    ) : RolloutEvent {
        override fun toString(): String = "advanced:$release:${percent(fromFraction)}->${percent(toFraction)}"
    }

    data class Completed(
        val release: ReleaseId,
        val fromFraction: Double,
    ) : RolloutEvent {
        override fun toString(): String = "completed:$release:${percent(fromFraction)}->100%"
    }

    data class Halted(
        val release: ReleaseId,
        val reason: String,
        val source: String,
    ) : RolloutEvent {
        override fun toString(): String = "halted:$release:$source"
    }

    data class GuardError(
        val message: String,
    ) : RolloutEvent {
        override fun toString(): String = "guard-error:$message"
    }
}
