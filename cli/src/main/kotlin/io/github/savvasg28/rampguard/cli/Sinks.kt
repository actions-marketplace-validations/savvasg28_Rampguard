package io.github.savvasg28.rampguard.cli

import io.github.savvasg28.rampguard.core.audit.AuditRenderer
import io.github.savvasg28.rampguard.core.model.DecisionRecord
import io.github.savvasg28.rampguard.core.model.RolloutEvent
import io.github.savvasg28.rampguard.core.ports.AuditLog
import io.github.savvasg28.rampguard.core.ports.Notifier
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Prints every event to stdout, so a run's log tells the story even without Slack. */
class StdoutNotifier(
    private val out: (String) -> Unit,
) : Notifier {
    override suspend fun notify(event: RolloutEvent) = out("notify: $event")
}

/** Fans one event out to several notifiers; a failing one does not stop the others. */
class CompositeNotifier(
    private val notifiers: List<Notifier>,
) : Notifier {
    override suspend fun notify(event: RolloutEvent) {
        var first: Exception? = null
        for (notifier in notifiers) {
            try {
                notifier.notify(event)
            } catch (e: Exception) {
                if (first == null) first = e
            }
        }
        first?.let { throw it }
    }
}

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

class CompositeAuditLog(
    private val logs: List<AuditLog>,
) : AuditLog {
    override suspend fun record(record: DecisionRecord) = logs.forEach { it.record(record) }
}

/** The GitHub Actions environment variables Rampguard reads. Each returns null outside Actions. */
object GitHubActions {
    fun summaryFile(env: Map<String, String>): Path? = env["GITHUB_STEP_SUMMARY"]?.let(Path::of)

    fun runUrl(env: Map<String, String>): String? {
        val server = env["GITHUB_SERVER_URL"] ?: return null
        val repository = env["GITHUB_REPOSITORY"] ?: return null
        val runId = env["GITHUB_RUN_ID"] ?: return null
        return "$server/$repository/actions/runs/$runId"
    }
}
