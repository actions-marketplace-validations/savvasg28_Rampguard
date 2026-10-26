package io.github.savvasg28.rampguard.core.policy

import io.github.savvasg28.rampguard.core.model.AppId

/** Turns a raw [PolicyFile] into a [Policy], collecting every problem instead of stopping at the first. */
internal object PolicyValidator {
    private const val MAX_PERCENT = 100

    fun validate(file: PolicyFile): PolicyResult {
        val errors = mutableListOf<ConfigError>()

        fun error(
            path: String,
            message: String,
        ) = errors.add(ConfigError(path, message))

        if (file.app.isBlank()) error("app", "must be the application package name")
        validateStages(file.stages, ::error)
        val webhook = file.notify?.webhook?.let { validateWebhook(it, ::error) }

        if (errors.isNotEmpty()) return PolicyResult.Invalid(errors)
        return PolicyResult.Valid(
            Policy(
                app = AppId(file.app.trim()),
                stages = file.stages,
                notify = NotifyConfig(slackWebhookEnv = file.notify?.slackWebhookEnv, webhook = webhook),
            ),
        )
    }

    private val headerName = Regex("^[A-Za-z0-9!#$%&'*+.^_`|~-]+$")

    private fun validateWebhook(
        file: WebhookFile,
        error: (String, String) -> Unit,
    ): WebhookConfig {
        if (file.urlEnv.isBlank()) error("notify.webhook.urlEnv", "must name the environment variable holding the URL")
        file.headersEnv.forEach { (name, envVar) ->
            if (!headerName.matches(name)) error("notify.webhook.headersEnv", "'$name' is not a valid header name")
            if (envVar.isBlank()) error("notify.webhook.headersEnv", "'$name' must name an environment variable")
        }
        return WebhookConfig(file.urlEnv.trim(), file.secretEnv?.trim()?.ifBlank { null }, file.headersEnv)
    }

    private fun validateStages(
        stages: List<Int>,
        error: (String, String) -> Unit,
    ) {
        if (stages.size < 2) {
            error("stages", "need at least two stages (the submitted one and 100)")
            return
        }
        if (stages.any { it <= 0 || it > MAX_PERCENT }) error("stages", "every stage must be within 1..100 percent")
        if (stages.zipWithNext().any { (a, b) -> b <= a }) error("stages", "must be strictly increasing; was $stages")
        if (stages.last() != MAX_PERCENT) {
            error("stages", "the last stage must be 100 (the rollout is completed, no 99.9% trick)")
        }
    }
}
