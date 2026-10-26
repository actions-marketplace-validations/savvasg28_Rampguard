package io.github.savvasg28.rampguard.core.policy

import io.github.savvasg28.rampguard.core.model.AppId

/** Tolerance when comparing user fractions read back from Play. */
const val FRACTION_EPSILON: Double = 1e-9

/**
 * The validated rollout policy. Build one with [PolicyParser]; the constructor assumes the invariants
 * [PolicyValidator] checks.
 *
 * @property stages rollout stages in percent, strictly increasing, last one 100. Each scheduled run moves the
 *   release from the stage its fraction is at to the next one.
 */
data class Policy(
    val app: AppId,
    val stages: List<Int>,
    val notify: List<Destination>,
) {
    val stageFractions: List<Double> = stages.map { it / 100.0 }
    val lastStageIndex: Int get() = stages.lastIndex

    fun fractionOf(stageIndex: Int): Double = stageFractions[stageIndex]

    /**
     * The index of the highest stage at or below [fraction], or -1 when the fraction is below the first stage. A
     * fraction between two stages counts as the lower one, so the next step is the stage just above it.
     */
    fun stageAtOrBelow(fraction: Double): Int = stageFractions.indexOfLast { it <= fraction + FRACTION_EPSILON }

    /** Whether the fraction sits exactly on a configured stage. */
    fun isOnStage(fraction: Double): Boolean = stageFractions.any { kotlin.math.abs(it - fraction) <= FRACTION_EPSILON }
}

/**
 * A configuration value that may be a secret. Written as `${NAME}` in the file, it is read from that environment
 * variable when Rampguard runs; anything else is used as written. Core never reads the environment, so a policy holds
 * the unresolved form.
 */
sealed interface Setting {
    data class Literal(
        val value: String,
    ) : Setting

    data class FromEnvironment(
        val variable: String,
    ) : Setting

    companion object {
        private val reference = Regex("""^\$\{([A-Za-z_][A-Za-z0-9_]*)}$""")

        /** `${NAME}` becomes [FromEnvironment]; everything else is a [Literal]. */
        fun parse(text: String): Setting {
            val match = reference.matchEntire(text.trim())
            return if (match != null) FromEnvironment(match.groupValues[1]) else Literal(text)
        }
    }
}

/** Where a run sends its events, in the order written. Every destination receives every event. */
sealed interface Destination {
    /** A Slack incoming webhook; the event becomes a short message. */
    data class Slack(
        val webhook: Setting,
    ) : Destination

    /**
     * Any HTTP endpoint; the event becomes a JSON document.
     *
     * @property signWith when set, each request carries `X-Rampguard-Signature: sha256=<hex HMAC-SHA256 of the body>`
     * @property headers extra request headers, for API keys and routing
     */
    data class Webhook(
        val url: Setting,
        val signWith: Setting?,
        val headers: Map<String, Setting>,
    ) : Destination
}
