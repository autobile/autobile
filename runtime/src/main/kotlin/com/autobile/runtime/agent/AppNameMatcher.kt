package com.autobile.runtime.agent

/**
 * Finds the installed app a person, or a model, meant by a name.
 *
 * Names arrive in every shape: "Sudoku", "sudoku.com", "Samsung Notes", "카카오톡", or a
 * package name. Matching normalises both sides to letters and digits, then prefers an
 * exact label, then a label that contains the name or is contained by it, then a package
 * name that contains it. Labels shorter than two characters never match by containment:
 * an app whose label normalises to nothing is contained in every name, and matching it
 * opened an arbitrary app for any request.
 */
object AppNameMatcher {

    /** An app that can be opened, with the label the launcher shows for it. */
    data class Candidate(val packageName: String, val label: String)

    fun bestMatch(name: String, candidates: List<Candidate>): String? {
        val raw = name.trim()
        if (raw.isEmpty()) return null
        candidates.firstOrNull { it.packageName.equals(raw, ignoreCase = true) }?.let { return it.packageName }

        val wanted = normalise(raw)
        if (wanted.length < MIN_MATCH_LENGTH) return null
        return candidates
            .asSequence()
            .map { candidate -> candidate to score(wanted, normalise(candidate.label), candidate.packageName.lowercase()) }
            .filter { (_, score) -> score > 0 }
            // Among equally good matches the shortest label is the most specific one:
            // "Notes" for "notes" rather than "Samsung Notes Add-ons".
            .sortedWith(compareByDescending<Pair<Candidate, Int>> { it.second }.thenBy { it.first.label.length })
            .firstOrNull()
            ?.first
            ?.packageName
    }

    private fun score(wanted: String, label: String, packageName: String): Int = when {
        label.isNotEmpty() && label == wanted -> EXACT
        label.length >= MIN_MATCH_LENGTH && (label.contains(wanted) || wanted.contains(label)) -> CONTAINS
        normalise(packageName).contains(wanted) -> PACKAGE
        else -> 0
    }

    private fun normalise(value: String): String = value.lowercase().replace(NON_WORD, "")

    private const val MIN_MATCH_LENGTH = 2
    private const val EXACT = 3
    private const val CONTAINS = 2
    private const val PACKAGE = 1
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
}
