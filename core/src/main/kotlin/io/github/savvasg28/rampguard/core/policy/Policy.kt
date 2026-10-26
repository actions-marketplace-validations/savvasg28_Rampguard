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
    val notify: NotifyConfig,
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

data class NotifyConfig(
    val slackWebhookEnv: String? = null,
    val webhook: WebhookConfig? = null,
)

/**
 * A generic JSON webhook for every event.
 *
 * @property urlEnv name of the environment variable that holds the URL
 * @property secretEnv name of the environment variable that holds the HMAC secret; when set, each request carries an
 *   `X-Rampguard-Signature: sha256=<hex>` header over the body
 * @property headersEnv extra request headers, header name to the name of the environment variable that holds the value
 */
data class WebhookConfig(
    val urlEnv: String,
    val secretEnv: String?,
    val headersEnv: Map<String, String>,
)
