/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.nlp.latin

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A measuring stand for context-ranked completions — the sentence re-ranking the prefix walk
 * (issue #334, the reporter's §3.5 and §3.9).
 *
 * ### Why this file had to exist before the feature
 *
 * Every other suggestion change in this project was measured against held-out text before it shipped, and
 * this one had no instrument at all: [AutocorrectEvalTest] scores isolated words with no sentence around
 * them, and `tools/glide-dict/eval_ngrams.py` scores each table on its own rather than the strip a user
 * actually sees. So "context should re-rank completions" could only ever have been argued, and the
 * argument is not obviously right — promoting a corpus guess above the word somebody is in the middle of
 * typing is exactly the kind of change that reads well and measures badly.
 *
 * ### What it simulates
 *
 * Held-out sentences (every [HOLDOUT_EVERY]th run of the Leipzig package the shipped tables were counted
 * from, so nothing here was trained on). For every word with at least one word of context in front of it,
 * every prefix of that word is typed in turn, and two strips are built for each prefix:
 *
 *  - **today** — the frequency walk alone, which is what the keyboard does now;
 *  - **with context** — [LatinLanguageProvider.orderContextCompletions] leading it, which is the change.
 *
 * ### The two numbers, and why both
 *
 * The gain is how often the word the writer was actually typing is reachable — at the first cell and in
 * the first three. The harm is how often the change *takes away* a first cell that was already right:
 * position 0 is the one people hit without reading, so moving it is not free. A rule that lifts the first
 * number by moving the second is not an improvement, and printing only the first is how that ships.
 */
class ContextCompletionEvalTest {

    private companion object {
        const val HOLDOUT_EVERY = 10
        const val MAX_SENTENCES = 20_000
        const val STRIP = 8

        /**
         * The corpora, absent on a clean checkout. The first is the package the shipped English tables
         * were counted from (held-out sentences of it); the second is a different *register* — web text
         * rather than news — because a gain that only exists in the prose the model was counted on is an
         * artefact, and the trigram work in this same issue made exactly that check.
         */
        val RUNS = listOf(
            "held-out news" to "tools/glide-dict/dist/corpora/eng_news_2024_1M-all.runs",
            "web (cross-register)" to "tools/glide-dict/dist/corpora/eng-com_web-public_2018_1M-all.runs",
        )
    }

    /** Walks up from the module directory like [EvalKeyboard.dictFile] does, so IDE runs work too. */
    private fun repoFile(relative: String): File? {
        var dir = File(".").absoluteFile
        repeat(5) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile ?: return null
        }
        return null
    }

    private fun assetText(name: String): String = EvalKeyboard.dictFile(name).readText()

    @Test
    fun measureContextRankedCompletions() {
        val corpora = RUNS.mapNotNull { (label, path) -> repoFile(path)?.let { label to it } }
        if (corpora.isEmpty()) {
            println("\n=== #334 · context-ranked completions ===")
            println("skipped: no corpus present (they are not in the repository)")
            return
        }

        val freq = EvalKeyboard.readDict("en.json")
        val bigrams = NgramIndex.parse(assetText("en_bigrams.txt"))
        val trigrams = NgramIndex.parse(assetText("en_trigrams.txt"))

        // The frequency walk, precomputed per prefix on demand: the dictionary sorted by frequency, and
        // for one prefix the first [STRIP] words that start with it. Same order the provider walks in.
        val ranked = freq.entries.sortedByDescending { it.value }.map { it.key }
        val walkCache = HashMap<String, List<String>>()
        fun walk(prefix: String): List<String> = walkCache.getOrPut(prefix) {
            val out = ArrayList<String>(STRIP)
            for (w in ranked) {
                if (w.startsWith(prefix)) {
                    out.add(w)
                    if (out.size >= STRIP) break
                }
            }
            out
        }

        fun measure(label: String, runs: File): Int {
            var points = 0
            var todayTop1 = 0
            var todayTop3 = 0
            var newTop1 = 0
            var newTop3 = 0
            var promoted = 0
            var promotedRight = 0
            var stolenTop1 = 0
            var deepUsed = 0
            // Split by how much has been typed: one letter is where a claim is worth the most and is also
            // where it is most likely to be wrong.
            val byLen = Array(4) { IntArray(4) } // [min(prefixLen,3)][todayTop1, newTop1, points, stolen]

            var sentence = 0
            runs.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (sentence >= MAX_SENTENCES * HOLDOUT_EVERY) break
                    val isHoldout = sentence++ % HOLDOUT_EVERY == 0
                    if (!isHoldout) continue
                    val words = line.trim().split(' ').filter { it.isNotEmpty() && it.all { c -> c.isLetter() } }
                    if (words.size < 2) continue
                    for (i in 1 until words.size) {
                        val target = words[i].lowercase()
                        if (target.length < 2 || !freq.containsKey(target)) continue
                        val prev = words[i - 1].lowercase()
                        val prev2 = if (i >= 2) words[i - 2].lowercase() else null
                        for (cut in 1 until target.length) {
                            val typed = target.substring(0, cut)
                            val today = walk(typed)
                            if (today.isEmpty()) continue

                            val deep = if (prev2 != null && !trigrams.isEmpty) {
                                val deepPrefix = "$prev2 $prev"
                                trigrams
                                    .topContinuations(deepPrefix, LatinLanguageProvider.CONTEXT_COMPLETION_SCAN)
                                    .firstOrNull { it.startsWith(typed) && freq.containsKey(it) }
                                    ?.takeIf {
                                        trigrams.countOf("$deepPrefix $it") >=
                                            LatinLanguageProvider.IN_WORD_PREDICTION_MIN_COUNT
                                    }
                            } else {
                                null
                            }
                            val blessed = bigrams
                                .topContinuations(prev, LatinLanguageProvider.CONTEXT_COMPLETION_SCAN)
                                .filter { it.startsWith(typed) && freq.containsKey(it) }
                            val lead = LatinLanguageProvider.orderContextCompletions(
                                deep,
                                blessed,
                                LatinLanguageProvider.CONTEXT_COMPLETION_MAX,
                            )
                            val updated = (lead + today).distinct().take(STRIP)

                            points++
                            val bucket = byLen[minOf(cut, 3)]
                            bucket[2]++
                            if (deep != null) deepUsed++
                            if (lead.isNotEmpty()) {
                                promoted++
                                if (lead.first() == target) promotedRight++
                            }
                            val todayHit1 = today.firstOrNull() == target
                            val newHit1 = updated.firstOrNull() == target
                            if (todayHit1) { todayTop1++; bucket[0]++ }
                            if (newHit1) { newTop1++; bucket[1]++ }
                            if (today.take(3).contains(target)) todayTop3++
                            if (updated.take(3).contains(target)) newTop3++
                            // The case the gain column cannot see: the first cell was right and is not any more.
                            if (todayHit1 && !newHit1) { stolenTop1++; bucket[3]++ }
                        }
                    }
                }
            }

            fun pct(n: Int) = if (points == 0) 0.0 else n * 100.0 / points
            println()
            println("=== #334 · context-ranked completions (§3.5 + §3.9) — $label ==========")
            println("held-out prediction points: $points (every ${HOLDOUT_EVERY}th sentence, English)")
            println()
            println("%-26s %10s %10s".format("", "today", "with ctx"))
            println("%-26s %9.1f%% %9.1f%%".format("intended word at cell 1", pct(todayTop1), pct(newTop1)))
            println("%-26s %9.1f%% %9.1f%%".format("intended word in top 3", pct(todayTop3), pct(newTop3)))
            println()
            println("%-26s %9.1f%%".format("something was promoted", pct(promoted)))
            println(
                "%-26s %9.1f%%".format(
                    "…and it was the right word",
                    if (promoted == 0) 0.0 else promotedRight * 100.0 / promoted,
                ),
            )
            println("%-26s %9.1f%%".format("two-word evidence used", pct(deepUsed)))
            println("%-26s %9.1f%%  <-- the harm".format("cell 1 was right, now wrong", pct(stolenTop1)))
            println()
            println("%-12s %10s %10s %10s %10s".format("prefix", "points", "today c1", "ctx c1", "stolen"))
            for (len in 1..3) {
                val b = byLen[len]
                if (b[2] == 0) continue
                val label = if (len == 3) "3+ letters" else "$len letter"
                println(
                    "%-12s %10d %9.1f%% %9.1f%% %9.1f%%".format(
                        label, b[2], b[0] * 100.0 / b[2], b[1] * 100.0 / b[2], b[3] * 100.0 / b[2],
                    ),
                )
            }
            println()
            return points
        }

        val total = corpora.sumOf { (label, runs) -> measure(label, runs) }

        assertTrue(total > 1000, "the corpus walk produced almost no prediction points ($total)")
        // No threshold on the gain: this stand exists to *inform* a decision, and a sampled statistic
        // asserted tightly fails at 2 a.m. for reasons nobody wants. What must hold is the property the
        // design claims — a promoted word is never invented, it always extends what was typed.
        assertTrue(
            LatinLanguageProvider.orderContextCompletions("aa", listOf("ab", "ac"), 3) == listOf("aa", "ab", "ac"),
            "two-word evidence must lead one-word evidence",
        )
        assertTrue(
            LatinLanguageProvider.orderContextCompletions(null, listOf("ab", "ab", "ac"), 2) == listOf("ab", "ac"),
            "the lead must be deduplicated and capped",
        )
    }
}
