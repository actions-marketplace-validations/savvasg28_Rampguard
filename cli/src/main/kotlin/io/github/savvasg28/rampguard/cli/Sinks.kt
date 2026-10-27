package io.github.savvasg28.rampguard.cli

import io.github.savvasg28.rampguard.core.audit.AuditRenderer
import io.github.savvasg28.rampguard.core.model.DecisionRecord
import io.github.savvasg28.rampguard.core.model.RolloutEvent
import io.github.savvasg28.rampguard.core.model.percent
import io.github.savvasg28.rampguard.core.ports.AuditLog
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** The audit record on stdout, as the job log. */
class StdoutAuditLog(
    private val out: (String) -> Unit,
) : AuditLog {
    override suspend fun record(record: DecisionRecord) = out(AuditRenderer.render(record).trimEnd())
}

/**
 * Appends the audit record to the GitHub Actions job summary (the file named by `GITHUB_STEP_SUMMARY`), so each run
 * shows its decision in the Actions tab. Does nothing when the variable is absent, e.g. on a laptop.
 */
class JobSummaryAuditLog(
    private val summaryFile: Path?,
) : AuditLog {
    override suspend fun record(record: DecisionRecord) {
        val path = summaryFile ?: return
        val text = "```\n" + AuditRenderer.render(record) + "```\n"
        Files.writeString(path, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }
}

/**
 * Publishes the record as GitHub Actions step outputs (the file named by `GITHUB_OUTPUT`), so later workflow steps
 * can act on it: `if: steps.rampguard.outputs.event == 'advanced'`. Does nothing when the variable is absent.
 *
 * The output names are a contract for workflows: add outputs, never rename them. Every output is always written, as
 * an empty string when it does not apply, so an expression never sees an undefined value.
 *
 * | Output | Value |
 * |---|---|
 * | `event` | What this run did to the live release: `advanced`, `completed`, `halted`, `failed` or `none`. |
 * | `release-name`, `version-codes` | The live release, when there is one. Version codes are comma-separated. |
 *
 * A dry run reports `none`, because nothing was done.
 * | `from-percent`, `to-percent` | The step taken, on `advanced` and `completed`. |
 * | `reason`, `source` | Why and who, on `halted`. |
 * | `ready-to-publish` | Name of a release approved by review that a person has to publish. |
 * | `rejected` | Name of a release rejected by review. |
 */
class StepOutputs(
    private val outputFile: Path?,
) : AuditLog {
    override suspend fun record(record: DecisionRecord) {
        val path = outputFile ?: return
        val lines =
            of(
                record,
            ).entries.joinToString("") { (name, value) -> "$name=${value.lineSequence().joinToString(" ")}\n" }
        Files.writeString(path, lines, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    companion object {
        val NAMES: List<String> =
            listOf(
                "event",
                "release-name",
                "version-codes",
                "from-percent",
                "to-percent",
                "reason",
                "source",
                "ready-to-publish",
                "rejected",
            )

        fun of(record: DecisionRecord): Map<String, String> {
            val events = record.events
            val halted = events.filterIsInstance<RolloutEvent.Halted>().firstOrNull()
            val step =
                events.firstNotNullOfOrNull {
                    when (it) {
                        is RolloutEvent.Advanced -> it.fromFraction to it.toFraction
                        is RolloutEvent.Completed -> it.fromFraction to 1.0
                        else -> null
                    }
                }
            val event =
                when {
                    events.any { it is RolloutEvent.GuardError } -> "failed"
                    record.applied != true -> "none"
                    halted != null -> "halted"
                    events.any { it is RolloutEvent.Completed } -> "completed"
                    step != null -> "advanced"
                    else -> "none"
                }
            val stepped = event == "advanced" || event == "completed"
            return mapOf(
                "event" to event,
                "release-name" to (record.release?.name ?: ""),
                "version-codes" to (record.release?.versionCodes?.joinToString(",") ?: ""),
                "from-percent" to if (stepped) plain(step!!.first) else "",
                "to-percent" to if (stepped) plain(step!!.second) else "",
                "reason" to (halted?.reason?.takeIf { event == "halted" } ?: ""),
                "source" to (halted?.source?.takeIf { event == "halted" } ?: ""),
                "ready-to-publish" to
                    events.filterIsInstance<RolloutEvent.ReadyToPublish>().joinToString(",") { it.release.name },
                "rejected" to events.filterIsInstance<RolloutEvent.Rejected>().joinToString(",") { it.release.name },
            )
        }

        /** `0.2` becomes `20`, as in the configuration; `0.005` becomes `0.50`. */
        private fun plain(fraction: Double): String = percent(fraction).removeSuffix("%")
    }
}

class CompositeAuditLog(
    private val logs: List<AuditLog>,
) : AuditLog {
    override suspend fun record(record: DecisionRecord) = logs.forEach { it.record(record) }
}
