/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.app.settings.dictionary

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import dev.patrickgold.jetpref.datastore.model.collectAsState as collectPrefAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.ime.dictionary.DictionaryManager
import dev.patrickgold.florisboard.ime.dictionary.LearnedWordEntry
import dev.patrickgold.florisboard.ime.dictionary.LearnedWordsStore
import dev.patrickgold.florisboard.ime.dictionary.UserDictionaryEntry
import dev.patrickgold.florisboard.ime.nlp.latin.WordLearningGate
import dev.patrickgold.florisboard.lib.FlorisLocale
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.florisboard.lib.compose.pluralsRes
import org.florisboard.lib.compose.stringRes

/** Frequency a promoted word is stored at, matching what a hand-added entry gets. */
private const val PROMOTED_FREQ = 255

/**
 * What the keyboard has picked up from typing, and the controls to correct it (issues #318, #375).
 *
 * This is deliberately a normal settings screen rather than a devtools page, for two reasons. A feature
 * that quietly builds a record of what someone writes owes them a way to look at it — that is most of what
 * makes it defensible at all. And it is the only way to *test* the feature: without it, "did it learn
 * that word?" can only be answered by typing until autocorrect stops interfering.
 *
 * ### Why it is three levels deep now
 *
 * It used to be one flat list of every word in every language and at every stage, with a single "forget
 * everything" above it. That put the most destructive control on the screen next to the least important
 * rows, and it made the one question people actually arrive with — *what has it picked up that I did not
 * ask for?* — impossible to answer without scrolling past everything that is working fine.
 *
 * So: language, then stage, then the words. The bulk action lives **inside** a stage, where its blast
 * radius is written on the button, and the stage that matters — the words that reached the dictionary —
 * says so in its own words before it lets go of them. The language level is skipped entirely when there
 * is only one, because a list of one is a tap that teaches nothing.
 */
@Composable
fun LearnedWordsScreen() = FlorisScreen {
    title = stringRes(R.string.settings__learned__title)
    previewFieldVisible = false

    content {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val learningSwitchOn by prefs.suggestion.learnTypedWords.collectPrefAsState()
        // Both, because the switch alone does not say whether anything is actually running: it is greyed
        // out while the dictionary a learned word graduates into is off, and a user who arrives here
        // wondering why nothing is being learned needs to be told *which* of the two it is (issue #375).
        val internalDictionaryOn by prefs.dictionary.enableFlorisUserDictionary.collectPrefAsState()
        val entries by LearnedWordsStore.flow(context).collectAsState(initial = emptyList())

        var pickedLang by remember { mutableStateOf<String?>(null) }
        var pickedStage by remember { mutableStateOf<WordLearningGate.Stage?>(null) }
        var confirmForget by remember { mutableStateOf<List<LearnedWordEntry>?>(null) }
        var confirmForgetAll by remember { mutableStateOf(false) }

        val byLang = remember(entries) { entries.groupBy { it.lang } }
        val languages = remember(byLang) { byLang.keys.sorted() }
        // A single language is not a choice, so it is not a screen. It also means that the overwhelmingly
        // common case — one keyboard language — sees exactly the two levels it needs.
        val lang = pickedLang ?: languages.singleOrNull()
        val stage = pickedStage

        BackHandler(enabled = stage != null || (pickedLang != null && languages.size > 1)) {
            if (stage != null) pickedStage = null else pickedLang = null
        }

        // Only above the folders. On the list of words it is four paragraphs of explanation between the
        // user and the thing they came to look at — the device made that obvious at a glance.
        if (stage == null) Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = stringRes(R.string.settings__learned__how_it_works),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val obstacle = when {
                !internalDictionaryOn -> R.string.settings__learned__disabled_dictionary
                !learningSwitchOn -> R.string.settings__learned__disabled
                else -> null
            }
            if (obstacle != null) {
                Text(
                    modifier = Modifier.padding(top = 8.dp),
                    text = stringRes(obstacle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (entries.isEmpty()) {
            Text(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                text = stringRes(R.string.settings__learned__empty),
                style = MaterialTheme.typography.bodyMedium,
            )
            return@content
        }

        /**
         * The one bulk control that is not inside a stage, shown only on the overview.
         *
         * The reporter asked for it to disappear, and the hazard he described was real: it used to sit
         * directly above the word rows and their own delete icons, so a slip near it undid a lot of
         * learning at once. It is kept — he offered that alternative himself — on two conditions he also
         * named: it now lives a level away from any per-row control, and it spares the words that reached
         * the dictionary, which is what its confirmation has always claimed and only now does.
         */
        @Composable
        fun ForgetEverythingButton() {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { confirmForgetAll = true }) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null)
                    Text(
                        modifier = Modifier.padding(start = 8.dp),
                        text = stringRes(R.string.settings__learned__forget_all),
                    )
                }
            }
        }

        if (confirmForgetAll) {
            AlertDialog(
                onDismissRequest = { confirmForgetAll = false },
                title = { Text(stringRes(R.string.settings__learned__forget_all)) },
                text = { Text(stringRes(R.string.settings__learned__forget_all_confirm)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmForgetAll = false
                            scope.launch { LearnedWordsStore.forgetAll(context) }
                        },
                    ) { Text(stringRes(R.string.settings__learned__forget_all)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmForgetAll = false }) {
                        Text(stringRes(R.string.action__cancel))
                    }
                },
            )
        }

        // ── Level 1: the languages ───────────────────────────────────────────────────────────────
        if (lang == null) {
            ForgetEverythingButton()
            for (code in languages) {
                val onPick: () -> Unit = { pickedLang = code }
                NavigationCard(
                    title = FlorisLocale.fromTag(code).displayName(),
                    subtitle = wordCount(byLang[code]?.size ?: 0),
                    onClick = onPick,
                )
            }
            return@content
        }

        val forLang = byLang[lang].orEmpty()
        val byStage = remember(forLang) { forLang.groupBy { stageOf(it) } }

        // ── Level 2: the stages ──────────────────────────────────────────────────────────────────
        if (stage == null) {
            Crumb(
                text = FlorisLocale.fromTag(lang).displayName(),
                onUp = if (languages.size > 1) ({ pickedLang = null }) else null,
            )
            // Only when this *is* the overview — with a second language above it, the button belongs
            // there rather than on every language's page.
            if (languages.size == 1) ForgetEverythingButton()
            for (candidate in WordLearningGate.Stage.entries) {
                val words = byStage[candidate].orEmpty()
                NavigationCard(
                    title = stringRes(tierTitleOf(candidate)),
                    subtitle = wordCount(words.size),
                    onClick = if (words.isNotEmpty()) ({ pickedStage = candidate }) else null,
                )
            }
            return@content
        }

        // ── Level 3: the words ───────────────────────────────────────────────────────────────────
        val words = byStage[stage].orEmpty().sortedByDescending { it.count }
        Crumb(
            text = "${FlorisLocale.fromTag(lang).displayName()} · ${stringRes(tierTitleOf(stage))}",
            onUp = { pickedStage = null },
        )
        if (words.isEmpty()) {
            // Reachable by emptying the stage you are standing in, rather than by navigating here — so
            // it needs its own wording: "nothing learned yet" is plainly false while the other stages
            // are full, and the device showed exactly that after promoting the last suggested word.
            Text(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                text = stringRes(R.string.settings__learned__empty_stage),
                style = MaterialTheme.typography.bodyMedium,
            )
            return@content
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = { confirmForget = words }) {
                Icon(Icons.Default.DeleteSweep, contentDescription = null)
                Text(
                    modifier = Modifier.padding(start = 8.dp),
                    text = stringRes(R.string.settings__learned__forget_tier),
                )
            }
        }
        for (entry in words) {
            LearnedWordRow(
                entry = entry,
                onForget = { scope.launch { forgetEverywhere(context, entry) } },
                onPromote = { scope.launch { promoteByHand(context, entry) } },
            )
        }

        confirmForget?.let { victims ->
            val promoted = victims.any { it.promoted }
            AlertDialog(
                onDismissRequest = { confirmForget = null },
                title = { Text(stringRes(R.string.settings__learned__forget_tier)) },
                text = {
                    Text(
                        stringRes(
                            // The stage that reached the dictionary gets its own wording, because that is
                            // the one where "forget" also means "take it back out of the dictionary".
                            if (promoted) {
                                R.string.settings__learned__forget_promoted_confirm
                            } else {
                                R.string.settings__learned__forget_tier_confirm
                            },
                            "words" to wordCount(victims.size),
                        ),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmForget = null
                            scope.launch {
                                for (entry in victims) forgetEverywhere(context, entry)
                            }
                        },
                    ) { Text(stringRes(R.string.settings__learned__forget_tier)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmForget = null }) {
                        Text(stringRes(R.string.action__cancel))
                    }
                },
            )
        }
    }
}

/**
 * "3 words" / "1 word", from the plural the rest of the app already uses (issue #375).
 *
 * The device showed "1 words" on the first run: a count pasted into a fixed plural is wrong in English
 * and wronger in the languages where the noun changes for more than two.
 */
@Composable
private fun wordCount(count: Int): String =
    pluralsRes(R.plurals.unit__words__written, count, "v" to count)

/** The stage a row is at — promotion is a fact about the row, everything else is about its score. */
private fun stageOf(entry: LearnedWordEntry): WordLearningGate.Stage = if (entry.promoted) {
    WordLearningGate.Stage.PROMOTED
} else {
    WordLearningGate.stageOf(
        WordLearningGate.decayedScore(entry.count, entry.lastUsed, System.currentTimeMillis() / 1000L),
    )
}

private fun tierTitleOf(stage: WordLearningGate.Stage): Int = when (stage) {
    WordLearningGate.Stage.REMEMBERED -> R.string.settings__learned__tier_remembered
    WordLearningGate.Stage.SUGGESTED -> R.string.settings__learned__tier_suggested
    WordLearningGate.Stage.PROMOTED -> R.string.settings__learned__tier_promoted
}

/** One level of the drill-down. [onClick] null when there is nothing behind it to look at. */
@Composable
private fun NavigationCard(title: String, subtitle: String, onClick: (() -> Unit)?) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (onClick != null) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onClick != null) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Where you are, and the way back up. */
@Composable
private fun Crumb(text: String, onUp: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onUp != null) {
            IconButton(onClick = onUp) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringRes(R.string.action__back),
                )
            }
        }
        Text(
            modifier = Modifier.padding(start = if (onUp != null) 0.dp else 8.dp),
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LearnedWordRow(
    entry: LearnedWordEntry,
    onForget: () -> Unit,
    onPromote: () -> Unit,
) {
    val stage = stageOf(entry)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.word,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    // Italic here for the same reason the suggestion strip uses it: this word came from
                    // you, not from the dictionary that shipped with the app.
                    fontStyle = FontStyle.Italic,
                )
                Text(
                    text = stringRes(
                        when (stage) {
                            WordLearningGate.Stage.REMEMBERED -> R.string.settings__learned__stage_remembered
                            WordLearningGate.Stage.SUGGESTED -> R.string.settings__learned__stage_suggested
                            WordLearningGate.Stage.PROMOTED -> R.string.settings__learned__stage_promoted
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringRes(
                        R.string.settings__learned__sightings,
                        "count" to entry.count,
                    ) + " · ${entry.lang}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!entry.promoted) {
                TextButton(onClick = onPromote) {
                    Text(stringRes(R.string.settings__learned__add_now))
                }
            }
            IconButton(onClick = onForget) {
                Icon(
                    imageVector = Icons.Default.DeleteSweep,
                    contentDescription = stringRes(R.string.settings__learned__forget),
                )
            }
        }
    }
}

/**
 * Forgets one word the way the keyboard's own long-press does: the observation **and**, when the word
 * had been promoted, the personal-dictionary entry it created (issue #375).
 *
 * These two were doing different things. Deleting a promoted word here left its dictionary copy behind,
 * so the word stayed protected from autocorrect and swipeable while this screen insisted it had been
 * forgotten — and the same button in the suggestion strip (`NlpManager.forgetLearnedWord`) removed both.
 * One instruction, two outcomes, depending on where it was given.
 */
private suspend fun forgetEverywhere(context: android.content.Context, entry: LearnedWordEntry) =
    withContext(Dispatchers.IO) {
        LearnedWordsStore.forget(context, entry)
        if (!entry.promoted) return@withContext
        // Same shape as [promoteByHand]: the manager throws when it was never initialised, which is
        // reachable from the settings app on a cold start, and both locales have to be tried because the
        // entry may have been written with the subtype's exact tag or with none at all.
        val dao = runCatching {
            DictionaryManager.default().also { it.loadUserDictionariesIfNecessary() }.florisUserDictionaryDao()
        }.getOrNull() ?: return@withContext
        runCatching {
            dao.queryExactFuzzyLocale(entry.word, FlorisLocale.default()).forEach { dao.delete(it) }
        }
    }

/**
 * Promotes one word on the user's say-so, skipping the sighting count entirely.
 *
 * The ladder exists to decide *without* the user; when they press the button they have decided, and
 * making them type the word two more times to confirm an explicit instruction would be silly.
 */
private suspend fun promoteByHand(context: android.content.Context, entry: LearnedWordEntry) =
    withContext(Dispatchers.IO) {
        // runCatching around default() as well: it throws when the manager was never initialised, which
        // is reachable from the settings app on a cold start, and a crash here would be a crash of the
        // whole settings screen rather than a button that quietly did nothing.
        val dao = runCatching {
            DictionaryManager.default().also { it.loadUserDictionariesIfNecessary() }.florisUserDictionaryDao()
        }.getOrNull() ?: return@withContext
        // Stored for **every** language rather than for `entry.lang`, and that is the fix for a bug the
        // device test caught: the learned store normalises its language to a bare code ("de") so a
        // vocabulary does not fall apart across de-DE and de-AT, but the personal dictionary is queried
        // with the subtype's full locale and matches only an exact tag or NULL. A word promoted here as
        // "de" was therefore written successfully, shown as "in your dictionary", and never suggested
        // again — the button appeared to work and did nothing.
        //
        // NULL is also the honest answer: this row says "the user pressed a button next to a word". It
        // does not say which regional variant they had in mind. (The automatic promotion path knows the
        // active subtype and keeps using its exact locale.)
        val inserted = runCatching {
            if (dao.queryExactFuzzyLocale(entry.word, FlorisLocale.default()).isEmpty()) {
                dao.insert(
                    UserDictionaryEntry(
                        id = 0,
                        word = entry.word,
                        freq = PROMOTED_FREQ,
                        locale = null,
                        shortcut = null,
                    )
                )
            }
            true
        }.getOrDefault(false)
        // Marked here too, or the row would keep decaying and offer the button again next week while the
        // dictionary entry it created sat there unexplained.
        if (inserted) LearnedWordsStore.setPromoted(context, entry.id, true, entry.lang)
    }
