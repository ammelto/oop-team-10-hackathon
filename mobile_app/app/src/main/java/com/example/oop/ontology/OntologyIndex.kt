package com.example.oop.ontology

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class OntologyIndex(private val context: Context) {
    private data class Shard(
        val records: List<OntologyRecord>,
        val termIndex: Map<String, IntArray>,
        val primaryIdIndex: Map<String, Int>,
    )

    private val loadMutex = Mutex()

    @Volatile
    private var snomed: Shard? = null

    @Volatile
    private var icd: Shard? = null

    @Volatile
    private var ais: Shard? = null

    val isLoaded: Boolean
        get() = snomed != null && icd != null && ais != null

    suspend fun load() {
        loadMutex.withLock {
            if (isLoaded) {
                return@withLock
            }
            withContext(Dispatchers.IO) {
                snomed = buildSnomed(OntologyPaths.snomedFile(context))
                icd = buildIcd(OntologyPaths.icdFile(context))
                ais = buildAis(OntologyPaths.aisFile(context))
            }
        }
    }

    fun snomedLookup(query: String, limit: Int = DEFAULT_LIMIT): List<OntologyMatch> =
        lookup(snomed ?: throw OntologyUnavailableException("snomed"), query, limit)

    fun icdLookup(query: String, limit: Int = DEFAULT_LIMIT): List<OntologyMatch> =
        lookup(icd ?: throw OntologyUnavailableException("icd10cm"), query, limit)

    fun aisLookup(query: String, limit: Int = DEFAULT_LIMIT): List<OntologyMatch> =
        lookup(ais ?: throw OntologyUnavailableException("ais1985"), query, limit)

    fun lookupAisByCode(code: String): OntologyMatch? {
        val shard = ais ?: throw OntologyUnavailableException("ais1985")
        val recordIndex = shard.primaryIdIndex[normalizePrimaryId(code)] ?: return null
        return OntologyMatch(
            record = shard.records[recordIndex],
            matchedField = "code",
            score = EXACT_CODE,
        )
    }

    private fun lookup(shard: Shard, query: String, limit: Int): List<OntologyMatch> {
        val sanitizedQuery = sanitize(query)
        if (sanitizedQuery.length < MIN_QUERY_LENGTH) {
            return emptyList()
        }

        shard.primaryIdIndex[normalizePrimaryId(query)]?.let { recordIndex ->
            return listOf(
                OntologyMatch(
                    record = shard.records[recordIndex],
                    matchedField = "code",
                    score = EXACT_CODE,
                ),
            )
        }

        val tokens = tokenize(query)
        if (tokens.isEmpty()) {
            return emptyList()
        }

        val postings = tokens.mapNotNull { shard.termIndex[it] }
        val candidateIds = when {
            postings.isEmpty() -> fallbackCandidates(shard.records, sanitizedQuery)
            postings.size == 1 -> postings.first().asList()
            else -> intersectPostings(postings).ifEmpty { unionPostings(postings) }
        }

        val cap = limit.coerceIn(1, MAX_LIMIT)
        return candidateIds
            .asSequence()
            .mapNotNull { scoreRecord(shard.records[it], sanitizedQuery, tokens) }
            .sortedByDescending(OntologyMatch::score)
            .take(cap)
            .toList()
    }

    private fun buildSnomed(file: File): Shard {
        ensureFileExists(file, "snomed")
        val records = ArrayList<OntologyRecord>()
        val termMap = HashMap<String, MutableList<Int>>()
        val primaryIdIndex = HashMap<String, Int>()

        file.useLines { lines ->
            lines.forEachIndexed { index, rawLine ->
                if (index == 0 && rawLine.startsWith("ConceptID")) {
                    return@forEachIndexed
                }

                val columns = rawLine.split('\t')
                if (columns.size < 4 || columns[1] != "1") {
                    return@forEachIndexed
                }

                val primaryId = columns[0].trim()
                val fsn = columns[2].trim().ifBlank { null }
                val preferredTerm = columns[3].trim().ifBlank { fsn ?: return@forEachIndexed }
                val record = OntologyRecord(
                    primaryId = primaryId,
                    preferredTerm = preferredTerm,
                    fullySpecifiedName = fsn,
                )
                val recordIndex = records.size
                records += record
                primaryIdIndex[normalizePrimaryId(primaryId)] = recordIndex
                indexRecord(
                    recordIndex = recordIndex,
                    fields = listOf(preferredTerm, fsn),
                    termMap = termMap,
                )
            }
        }

        return Shard(
            records = records,
            termIndex = freezeTermIndex(termMap),
            primaryIdIndex = primaryIdIndex,
        )
    }

    private fun buildIcd(file: File): Shard {
        ensureFileExists(file, "icd10cm")
        val records = ArrayList<OntologyRecord>()
        val termMap = HashMap<String, MutableList<Int>>()
        val primaryIdIndex = HashMap<String, Int>()

        file.useLines { lines ->
            lines.forEach { rawLine ->
                val match = ICD_LINE_REGEX.matchEntire(rawLine.trim()) ?: return@forEach
                val primaryId = match.groupValues[1].trim()
                val preferredTerm = match.groupValues[2].trim()
                val record = OntologyRecord(
                    primaryId = primaryId,
                    preferredTerm = preferredTerm,
                )
                val recordIndex = records.size
                records += record
                primaryIdIndex[normalizePrimaryId(primaryId)] = recordIndex
                indexRecord(recordIndex, listOf(preferredTerm), termMap)
            }
        }

        return Shard(
            records = records,
            termIndex = freezeTermIndex(termMap),
            primaryIdIndex = primaryIdIndex,
        )
    }

    private fun buildAis(file: File): Shard {
        ensureFileExists(file, "ais1985")
        val records = ArrayList<OntologyRecord>()
        val termMap = HashMap<String, MutableList<Int>>()
        val primaryIdIndex = HashMap<String, Int>()
        var currentSection: String? = null

        file.useLines { lines ->
            lines.forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isBlank() || line.startsWith("===")) {
                    return@forEach
                }

                if (SECTION_REGEX.matches(line)) {
                    currentSection = line
                    return@forEach
                }

                val match = AIS_ROW_REGEX.matchEntire(line) ?: return@forEach
                val primaryId = match.groupValues[1]
                val preferredTerm = match.groupValues[2].trim()
                val severity = AisSeverity.fromDigit(primaryId.last())
                val record = OntologyRecord(
                    primaryId = primaryId,
                    preferredTerm = preferredTerm,
                    section = currentSection,
                    bodyRegion = AisBodyRegion.labelFor(primaryId.first()),
                    severity = severity,
                )
                val recordIndex = records.size
                records += record
                primaryIdIndex[normalizePrimaryId(primaryId)] = recordIndex
                indexRecord(recordIndex, listOf(preferredTerm, currentSection), termMap)
            }
        }

        return Shard(
            records = records,
            termIndex = freezeTermIndex(termMap),
            primaryIdIndex = primaryIdIndex,
        )
    }

    private fun ensureFileExists(file: File, source: String) {
        if (!file.exists()) {
            throw OntologyUnavailableException(source)
        }
    }

    private fun indexRecord(
        recordIndex: Int,
        fields: List<String?>,
        termMap: MutableMap<String, MutableList<Int>>,
    ) {
        fields.asSequence()
            .filterNotNull()
            .flatMap { tokenize(it).asSequence() }
            .distinct()
            .forEach { token ->
                termMap.getOrPut(token) { ArrayList() }.add(recordIndex)
            }
    }

    private fun freezeTermIndex(termMap: Map<String, MutableList<Int>>): Map<String, IntArray> =
        termMap.mapValues { (_, postings) ->
            postings.sort()
            postings.toIntArray()
        }

    private fun fallbackCandidates(records: List<OntologyRecord>, query: String): List<Int> =
        records.asSequence()
            .withIndex()
            .filter {
                val preferred = sanitize(it.value.preferredTerm)
                val fsn = sanitize(it.value.fullySpecifiedName.orEmpty())
                preferred.contains(query) || fsn.contains(query)
            }
            .take(MAX_CANDIDATES)
            .map { it.index }
            .toList()

    private fun intersectPostings(postings: List<IntArray>): List<Int> {
        val sorted = postings.sortedBy(IntArray::size)
        val acc = sorted.first().toMutableSet()
        sorted.drop(1).forEach { posting ->
            acc.retainAll(posting.asIterable().toSet())
            if (acc.isEmpty()) {
                return emptyList()
            }
        }
        return acc.take(MAX_CANDIDATES)
    }

    private fun unionPostings(postings: List<IntArray>): List<Int> =
        buildSet {
            postings.forEach { posting ->
                posting.forEach { add(it) }
            }
        }.take(MAX_CANDIDATES)

    private fun scoreRecord(
        record: OntologyRecord,
        query: String,
        tokens: List<String>,
    ): OntologyMatch? {
        val preferred = sanitize(record.preferredTerm)
        val fsn = sanitize(record.fullySpecifiedName.orEmpty())

        val baseScore = when {
            preferred == query -> EXACT_PREFERRED to "preferred"
            preferred.startsWith(query) -> PREFIX_PREFERRED to "preferred"
            fsn == query -> EXACT_FSN to "fsn"
            preferred.contains(query) -> CONTAINS_PREFERRED to "preferred"
            fsn.contains(query) -> CONTAINS_FSN to "fsn"
            tokens.all(preferred::contains) -> TOKEN_MATCH to "preferred"
            tokens.all(fsn::contains) -> TOKEN_MATCH to "fsn"
            else -> null
        } ?: return null

        return OntologyMatch(
            record = record,
            matchedField = baseScore.second,
            score = baseScore.first + tokens.size,
        )
    }

    private fun sanitize(value: String): String =
        value.lowercase(Locale.US)
            .replace(CONTROL_REGEX, " ")
            .replace(NON_WORD_REGEX, " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()

    private fun tokenize(value: String): List<String> =
        sanitize(value)
            .split(' ')
            .asSequence()
            .filter { token -> token.length >= MIN_QUERY_LENGTH && token !in STOP_WORDS }
            .toList()

    private fun normalizePrimaryId(value: String): String = value.trim().uppercase(Locale.US)

    private fun IntArray.asList(): List<Int> = asIterable().take(MAX_CANDIDATES).toList()

    private companion object {
        private const val DEFAULT_LIMIT = 5
        private const val MAX_LIMIT = 16
        private const val MAX_CANDIDATES = 4096
        private const val MIN_QUERY_LENGTH = 2

        private const val EXACT_CODE = 1200
        private const val EXACT_PREFERRED = 1000
        private const val PREFIX_PREFERRED = 700
        private const val EXACT_FSN = 600
        private const val CONTAINS_PREFERRED = 200
        private const val CONTAINS_FSN = 100
        private const val TOKEN_MATCH = 80

        private val ICD_LINE_REGEX = Regex("^(\\S+)\\s+(.+)$")
        private val AIS_ROW_REGEX = Regex("^(\\d{5}\\.\\d)\\s+(.+)$")
        private val SECTION_REGEX = Regex("^[A-Z0-9][A-Z0-9\\- /,&()]+$")
        private val NON_WORD_REGEX = Regex("[^a-z0-9.]+")
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val CONTROL_REGEX = Regex("\\p{Cntrl}+")
        private val STOP_WORDS = setOf("a", "an", "and", "of", "or", "the", "with", "without")
    }
}
