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
    val plan = planSteps(utterance, matchClause) ?: return null
    return plan.steps.takeIf { plan.leftover.isEmpty() && it.size >= 2 }
}

/** Phone steps from a compound sentence, plus clauses that aren't phone actions. */
internal data class PartialPlan(val steps: List<PlanStep>, val leftover: List<String>)

private val tellPronoun = Regex("""^(?:and\s+)?(?:tell|ask|say\s+to|inform)\s+(?:him|her|them)\s+(?:that\s+|to\s+)?(.+)$""")

/**
 * "Call Kaif and tell him I'm late" is one call; "open WhatsApp and message Kaif hi"
 * is one message; "WhatsApp Kaif and tell him I'm late" fills the message.
 * Returns null when no clause is a phone action.
 */
internal fun planSteps(utterance: String, matchClause: (String) -> LocalMatch?): PartialPlan? {
    val clauses = splitActionClauses(utterance)
    if (clauses.size < 2) return null
    val steps = mutableListOf<PlanStep>()
    val leftover = mutableListOf<String>()
    for (clause in clauses) {
        val match = matchClause(clause)
        val last = steps.lastOrNull()?.command
        if (match != null) {
            val opensWhatsApp = last is LocalCommand.LaunchAppByName && last.appName.contains("whatsapp", ignoreCase = true)
            val usesWhatsApp = match.command is LocalCommand.SendWhatsApp || match.command is LocalCommand.WhatsAppCall
            if (opensWhatsApp && usesWhatsApp) steps.removeAt(steps.lastIndex)
            steps += PlanStep(label = match.spokenLabel, command = match.command)
            continue
        }
        val told = tellPronoun.find(clause.trim().lowercase())?.let { clause.trim().takeLast(it.groupValues[1].length) }
        when {
            told != null && (last is LocalCommand.CallContact || last is LocalCommand.WhatsAppCall) -> Unit
            told != null && last is LocalCommand.SendWhatsApp -> {
                val filled = last.copy(
                    options = last.options.map { option ->
                        option.copy(message = listOf(option.message, told).filter { it.isNotBlank() }.joinToString(" "))
                    },
                )
                steps[steps.lastIndex] = steps.last().copy(command = filled)
            }
            else -> leftover += clause
        }
    }
    if (steps.isEmpty()) return null
    return PartialPlan(steps, leftover)
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
