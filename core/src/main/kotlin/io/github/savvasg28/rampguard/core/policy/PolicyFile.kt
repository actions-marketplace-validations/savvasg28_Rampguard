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
    val notify: List<DestinationFile> = emptyList(),
)

/** One list item under `notify`. Exactly one of the keys is set; the key names the kind of destination. */
@Serializable
internal data class DestinationFile(
    val slack: String? = null,
    val webhook: WebhookFile? = null,
)

@Serializable
internal data class WebhookFile(
    val url: String,
    val signWith: String? = null,
    val headers: Map<String, String> = emptyMap(),
)
