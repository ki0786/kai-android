package com.kai.assistant.tools

data class PlanStep(
    val label: String,
    val command: LocalCommand,
)

data class StepReport(
    val ok: Boolean,
    val detail: String,
)

/**
 * Splits a sentence into local phone actions. Returns null unless every clause
 * is a tool this app already runs, and there are at least two.
 */
internal fun planLocalSteps(utterance: String, matchClause: (String) -> LocalMatch?): List<PlanStep>? {
    val clauses = splitActionClauses(utterance)
    if (clauses.size < 2) return null
    return clauses.map { clause ->
        val match = matchClause(clause) ?: return null
        PlanStep(label = match.spokenLabel, command = match.command)
    }
}

internal fun splitActionClauses(raw: String): List<String> {
    val text = raw.trim().replace(Regex("[?.!]+$"), "").trim()
    if (text.isBlank()) return emptyList()
    return text.split(
        Regex(
            """\s*,\s*and\s+|\s+and then\s+|\s*,\s*then\s+|\s+then\s+|\s+and\s+|;\s*""",
            RegexOption.IGNORE_CASE,
        ),
    ).map { it.trim() }.filter { it.isNotEmpty() }
}

internal fun shouldRetry(failed: Boolean, alreadyRetried: Boolean): Boolean = failed && !alreadyRetried

internal fun planIntro(count: Int): String = "I'll do $count steps on this phone."

internal fun planStepLine(index: Int, total: Int, label: String): String =
    "Step ${index + 1} of $total: $label."

internal fun planSummary(reports: List<StepReport>): String {
    if (reports.isEmpty()) return "I didn't run any steps."
    return reports.joinToString(" ") { report ->
        if (report.ok) "Finished: ${report.detail}" else "Failed: ${report.detail}"
    }
}
