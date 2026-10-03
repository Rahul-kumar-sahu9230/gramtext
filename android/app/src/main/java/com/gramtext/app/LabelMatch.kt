package com.gramtext.app

import kotlin.math.abs

private val NOT_WORD = Regex("""[^\p{L}\p{M}\p{N}]+""") // \p{M} keeps Devanagari matras

private fun words(text: String): List<String> =
    text.lowercase().split(NOT_WORD).filter { it.isNotEmpty() }

/** Equal, or one character inserted/removed/changed (only for words of 4+ characters). */
private fun nearlyEqual(a: String, b: String): Boolean {
    if (a == b) return true
    if (minOf(a.length, b.length) < 4 || abs(a.length - b.length) > 1) return false
    var i = 0
    var j = 0
    var edits = 0
    while (i < a.length && j < b.length) {
        if (a[i] == b[j]) {
            i++; j++
            continue
        }
        if (++edits > 1) return false
        when {
            a.length > b.length -> i++
            a.length < b.length -> j++
            else -> { i++; j++ }
        }
    }
    return edits + (a.length - i) + (b.length - j) <= 1
}

/**
 * Same label as before? Two camera frames of one label rarely give identical OCR: a line at
 * the box edge comes and goes, lines swap order, a character differs. So compare WORDS: it is
 * the same label when at least 60% of the new words also appear (exactly or nearly) in the
 * previous reading.
 */
internal fun sameLabel(text: String, previous: String): Boolean {
    val new = words(text)
    val old = words(previous)
    if (new.isEmpty() || old.isEmpty()) return false
    val oldSet = old.toHashSet()
    val matched = new.count { w -> w in oldSet || old.any { nearlyEqual(w, it) } }
    return matched >= 0.6 * new.size
}
