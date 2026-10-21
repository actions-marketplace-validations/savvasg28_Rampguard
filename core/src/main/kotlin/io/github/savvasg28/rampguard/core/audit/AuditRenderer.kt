package io.github.savvasg28.rampguard.core.audit

import io.github.savvasg28.rampguard.core.model.DecisionRecord
import io.github.savvasg28.rampguard.core.model.percent

/**
 * Renders a [DecisionRecord] as deterministic text. The same record always renders to the same
 * bytes, which is what the simulator's determinism check relies on. Markdown-friendly for job summaries.
 */
object AuditRenderer {
    fun render(record: DecisionRecord): String =
        buildString {
            val verb = if (record.applied == false) "WOULD " else ""
            appendLine("${record.at} ${record.command} ${record.app}" + if (record.dryRun) " dry-run" else "")
            appendLine(
                "  release: ${record.release ?: "-"}" + (record.observedFraction?.let { " at ${percent(it)}" } ?: "") +
                    (record.stageIndex?.let { " stage=$it" } ?: ""),
            )
            appendLine("  decision: $verb${record.decision}" + (record.applied?.let { " applied=$it" } ?: ""))
            record.notes.forEach { appendLine("  note: $it") }
            record.events.forEach { appendLine("  notify: $it") }
        }

    fun renderAll(records: List<DecisionRecord>): String = records.joinToString("") { render(it) }
}
