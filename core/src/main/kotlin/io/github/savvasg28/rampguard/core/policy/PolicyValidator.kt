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
        val notify =
            file.notify.mapIndexedNotNull {
                index,
                item,
                ->
                validateDestination("notify[$index]", item, ::error)
            }

        if (errors.isNotEmpty()) return PolicyResult.Invalid(errors)
        return PolicyResult.Valid(Policy(app = AppId(file.app.trim()), stages = file.stages, notify = notify))
    }

    private val headerName = Regex("^[A-Za-z0-9!#$%&'*+.^_`|~-]+$")

    private fun validateDestination(
        path: String,
        item: DestinationFile,
        error: (String, String) -> Unit,
    ): Destination? {
        val kinds = listOfNotNull(item.slack?.let { "slack" }, item.webhook?.let { "webhook" })
        if (kinds.size != 1) {
            error(path, "must be exactly one of 'slack' or 'webhook'; was ${kinds.ifEmpty { listOf("nothing") }}")
            return null
        }
        return when {
            item.slack != null -> setting("$path.slack", item.slack, error)?.let(Destination::Slack)
            else -> validateWebhook(path, item.webhook!!, error)
        }
    }

    private fun validateWebhook(
        path: String,
        file: WebhookFile,
        error: (String, String) -> Unit,
    ): Destination? {
        val url = setting("$path.webhook.url", file.url, error)
        val signWith = file.signWith?.let { setting("$path.webhook.signWith", it, error) }
        val headers =
            file.headers.mapNotNull { (name, value) ->
                if (!headerName.matches(name)) {
                    error("$path.webhook.headers", "'$name' is not a valid header name")
                    return@mapNotNull null
                }
                setting("$path.webhook.headers.$name", value, error)?.let { name to it }
            }
        return url?.let { Destination.Webhook(it, signWith, headers.toMap()) }
    }

    /** A non-blank value, parsed as a [Setting]. A value that looks like a reference but is not one is an error. */
    private fun setting(
        path: String,
        text: String,
        error: (String, String) -> Unit,
    ): Setting? {
        if (text.isBlank()) {
            error(path, "must not be blank")
            return null
        }
        val parsed = Setting.parse(text)
        if (parsed is Setting.Literal && text.contains("\${")) {
            error(path, "a secret is written as \${NAME} for the whole value, naming an environment variable")
            return null
        }
        return parsed
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
