package io.github.savvasg28.rampguard.core.policy

import com.charleskorn.kaml.UnknownPropertyException
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlException
import kotlinx.serialization.SerializationException

/** A configuration problem, located by a YAML path such as `stages`. */
data class ConfigError(
    val path: String,
    val message: String,
) {
    override fun toString(): String = "$path: $message"
}

sealed interface PolicyResult {
    data class Valid(
        val policy: Policy,
    ) : PolicyResult

    data class Invalid(
        val errors: List<ConfigError>,
    ) : PolicyResult {
        fun render(): String = errors.joinToString("\n") { "  - $it" }
    }

    fun policyOrThrow(): Policy =
        when (this) {
            is Valid -> policy
            is Invalid -> throw InvalidPolicyException(this)
        }
}

class InvalidPolicyException(
    val result: PolicyResult.Invalid,
) : IllegalArgumentException("invalid rampguard configuration:\n${result.render()}")

/**
 * Parses the YAML configuration. Strict: unknown keys, wrong types and every validation rule in
 * [PolicyValidator] are reported as errors rather than exceptions. Pure: takes text, never touches files.
 */
object PolicyParser {
    private val yaml =
        Yaml(
            configuration =
                YamlConfiguration(
                    strictMode = true,
                ),
        )

    fun parse(text: String): PolicyResult {
        val file =
            try {
                yaml.decodeFromString(PolicyFile.serializer(), text)
            } catch (e: UnknownPropertyException) {
                return PolicyResult.Invalid(listOf(unknownProperty(e)))
            } catch (e: YamlException) {
                val path = e.path.toHumanReadableString().ifBlank { "<root>" }
                return PolicyResult.Invalid(listOf(ConfigError(path, e.message ?: "invalid YAML")))
            } catch (e: SerializationException) {
                return PolicyResult.Invalid(listOf(ConfigError("<root>", e.message ?: "invalid configuration")))
            }
        return PolicyValidator.validate(file)
    }

    private fun unknownProperty(e: UnknownPropertyException): ConfigError {
        val path = e.path.toHumanReadableString().ifBlank { "<root>" }
        val hint =
            when (e.propertyName) {
                in REMOVED_KEYS ->
                    " ('${e.propertyName}' was removed, see ADR 0010 to 0012; a dry run is the --dry-run flag)"
                "notify" -> " ('notify' was removed, see ADR 0013; the action reports through step outputs)"
                else -> ""
            }
        return ConfigError(path, (e.message ?: "unknown property '${e.propertyName}'") + hint)
    }

    private val REMOVED_KEYS =
        setOf("gates", "windowHours", "minSoakHours", "blackout", "guardErrorAfterFailures", "mode")
}
