package com.example.oop.ontology

data class TriggerMatch(
    val entry: TriggerEntry,
    val matchedPhrase: String,
    val pointOfInterest: String,
    val span: IntRange,
    val negated: Boolean,
)

class TriggerIndex(entries: List<TriggerEntry>) {
    private data class CompiledPhrase(
        val entry: TriggerEntry,
        val regex: Regex,
    )

    private val compiled: List<CompiledPhrase> =
        entries.map { entry ->
            CompiledPhrase(
                entry = entry,
                regex = Regex("\\b${buildPhrasePattern(entry.phrase)}\\b"),
            )
        }

    fun match(text: String): List<TriggerMatch> {
        if (text.isBlank()) {
            return emptyList()
        }
        val normalized = text.lowercase()
        val hits =
            compiled.mapNotNull { compiledPhrase ->
                val hit = compiledPhrase.regex.find(normalized) ?: return@mapNotNull null
                TriggerMatch(
                    entry = compiledPhrase.entry,
                    matchedPhrase = compiledPhrase.entry.phrase,
                    pointOfInterest = compiledPhrase.entry.pointOfInterest,
                    span = hit.range,
                    negated = isNegated(normalized, hit.range.first),
                )
            }
        return hits
            .sortedBy { it.span.first }
            .distinctBy { it.entry.id }
    }

    private fun isNegated(text: String, startIndex: Int): Boolean {
        val windowStart = (startIndex - NEGATION_LOOKBACK_CHARS).coerceAtLeast(0)
        return NEGATION_REGEX.containsMatchIn(text.substring(windowStart, startIndex))
    }

    private fun buildPhrasePattern(phrase: String): String =
        phrase
            .trim()
            .split(WHITESPACE_REGEX)
            .joinToString("\\s+") { Regex.escape(it) }

    private companion object {
        const val NEGATION_LOOKBACK_CHARS = 30
        val WHITESPACE_REGEX = Regex("\\s+")
        val NEGATION_REGEX = Regex("\\b(no|without|denies|negative for|no sign of)\\b")
    }
}
