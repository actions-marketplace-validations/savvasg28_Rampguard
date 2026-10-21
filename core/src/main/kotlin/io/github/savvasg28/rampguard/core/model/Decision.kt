package io.github.savvasg28.rampguard.core.model

import java.util.Locale

/** What a run does. Only [isWrite] decisions touch Play. */
sealed interface Decision {
    val isWrite: Boolean get() = false

    /** Set the rollout to the given stage. */
    data class Advance(
        val toStageIndex: Int,
        val toFraction: Double,
    ) : Decision {
        override val isWrite: Boolean get() = true

        override fun toString(): String = "Advance(to=${percent(toFraction)})"
    }

    /** Complete the rollout (100%). */
    data object Complete : Decision {
        override val isWrite: Boolean get() = true

        override fun toString(): String = "Complete"
    }

    data class Halt(
        val reason: String,
    ) : Decision {
        override val isWrite: Boolean get() = true

        override fun toString(): String = "Halt($reason)"
    }

    data class NoOp(
        val reason: String,
    ) : Decision {
        override fun toString(): String = "NoOp($reason)"
    }
}

/** Deterministic, locale-independent percentage rendering used in decisions, events and audit. */
fun percent(fraction: Double): String {
    val pct = fraction * 100.0
    return if (pct == Math.rint(pct)) {
        String.format(Locale.ROOT, "%d%%", pct.toLong())
    } else {
        String.format(Locale.ROOT, "%.2f%%", pct)
    }
}
