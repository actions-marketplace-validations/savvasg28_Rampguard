package io.github.savvasg28.rampguard.core.policy

import kotlinx.serialization.Serializable

/**
 * The raw shape of `.github/rampguard.yml`, exactly as written. Parsed strictly (unknown keys fail), then checked by
 * [PolicyValidator] and turned into a [Policy]. Keep this file in step with the config reference in the README.
 */
@Serializable
internal data class PolicyFile(
    val app: String,
    val stages: List<Int>,
    val notify: NotifyFile? = null,
)

@Serializable
internal data class NotifyFile(
    val slackWebhookEnv: String? = null,
    val webhook: WebhookFile? = null,
)

@Serializable
internal data class WebhookFile(
    val urlEnv: String,
    val secretEnv: String? = null,
    val headersEnv: Map<String, String> = emptyMap(),
)
