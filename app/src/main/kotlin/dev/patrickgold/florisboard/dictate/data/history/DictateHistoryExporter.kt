/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.data.history

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.provider.AudioContainer
import dev.patrickgold.florisboard.lib.devtools.flogError
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Writes every finished dictation into a folder the user picked (issue #379), so whatever they already
 * run on that folder — a sync client, a script, an agent reading their day back — can pick it up without
 * anyone opening this app.
 *
 * The deliberate non-feature is the account: there is no Dropbox or Drive login here, no token, no
 * upload. Dictate writes a file through the Storage Access Framework and stops there; carrying that file
 * anywhere is the job of whatever the user already trusts with that folder. It keeps the promise that
 * nothing leaves the device unless they sent it, and it works for every service at once.
 *
 * **One file per dictation, written once.** A single growing file plus any sync client is a machine for
 * producing "(conflicted copy)" — so the transcript of each entry gets its own `.txt`, named
 * `2026-09-14_18-31-26_42.txt`: a sortable local timestamp, and the entry id last so a plain folder
 * listing says which dictations are already there. That is what makes [exportAll] a catch-up rather
 * than a duplicator, and it is why no "exported" column had to be added to the table.
 *
 * Failure is best-effort and visible rather than queued: there is no WorkManager in this project, so a
 * write attempted while the folder is unreachable is simply lost. It stamps
 * [FlorisPreferenceModel.Dictate.historyExportLastFailure] so the settings row can say so, and
 * [exportAll] is the way back.
 */
object DictateHistoryExporter {

    /** Serializes writes: two dictations finishing in quick succession must not race on the folder. */
    private val writeLock = Mutex()

    /**
     * Fire-and-forget writes live here, never on the caller's scope.
     *
     * A SAF write against a cloud-backed provider is a network call that can block for seconds or fail
     * outright offline, and the caller is the path that has just put a transcript into someone's text
     * field. That path must stay exactly as fast as it was before this feature existed.
     */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Result of a bulk run, for the summary the dialog shows afterwards. */
    data class BulkResult(val written: Int, val skipped: Int, val failed: Int)

    /** The picked folder, or null when none is set. */
    fun folderUri(prefs: FlorisPreferenceModel): Uri? =
        prefs.dictate.historyExportFolderUri.get().takeIf { it.isNotBlank() }?.let(Uri::parse)

    /**
     * Whether the persisted grant on [treeUri] still allows writing.
     *
     * Asked rather than assumed, for the same reason [dev.patrickgold.florisboard.dictate.sticker.StickerWriter.canWrite]
     * asks: a grant can be revoked, the folder can be on storage that is no longer there, and a backup
     * restores this preference onto a device that never had the grant at all. All three have to read as
     * "folder unavailable" in the UI, not as a feature that silently stopped working.
     */
    fun canWrite(context: Context, treeUri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == treeUri && it.isWritePermission }

    /** Display name of [treeUri], for the settings row, without making the UI touch SAF itself. */
    fun folderName(context: Context, treeUri: Uri): String {
        val docUri = try {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        } catch (e: Exception) {
            return treeUri.lastPathSegment.orEmpty()
        }
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return try {
            context.contentResolver.query(docUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: treeUri.lastPathSegment.orEmpty()
        } catch (e: Exception) {
            treeUri.lastPathSegment.orEmpty()
        }
    }

    /** Takes the persisted read+write grant on a freshly picked [treeUri]; false when Android refused. */
    fun takeGrant(context: Context, treeUri: Uri): Boolean = try {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        true
    } catch (e: SecurityException) {
        false
    }

    /**
     * Gives up the grant on [previous] when the folder changes or is disconnected.
     *
     * Persisted URI permissions are a capped per-app resource, so holding on to every folder anyone ever
     * picked would eventually cost the one they are using now.
     */
    fun releaseGrant(context: Context, previous: String, keep: String = "") {
        if (previous.isBlank() || previous == keep) return
        try {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(previous),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            // Already gone — revoked, or the folder was removed. Nothing to release.
        }
    }

    /**
     * Queues entry [id] to be written, if a folder is configured. Returns immediately.
     *
     * Called from [DictateHistoryStore] rather than from the controller on purpose: the transcript
     * reaches the table through three different doors (a finished dictation, the completion of the
     * placeholder row from issue #358, and a re-transcribe overwriting an entry in place) and a fourth
     * caller entirely — the share-import screen, which talks to the store directly. One hook at the
     * store covers all of them, and inherits the privacy gate that already ran further upstream:
     * anything that got as far as a row has passed `isSensitiveDictationField`.
     */
    fun enqueue(context: Context, id: Long) {
        val appContext = context.applicationContext
        val prefs by FlorisPreferenceStore
        if (prefs.dictate.historyExportFolderUri.get().isBlank()) return
        scope.launch {
            val entry = runCatching { DictateHistoryStore.getById(appContext, id) }.getOrNull() ?: return@launch
            exportEntry(appContext, entry)
        }
    }

    /**
     * Writes one entry's transcript (and its audio, when asked for) into the folder. Already-written
     * entries are overwritten in place, which is what a re-transcribe needs.
     *
     * Failed entries are refused, and that single check carries more weight than it looks: since issue
     * #358 the row that a dictation creates when its audio is *sent* also carries `failed = true`, with
     * a translated placeholder where the transcript will be. Exporting on insert alone would fill the
     * folder with files reading "Transcription failed" — and a genuinely failed entry holds the same UI
     * string, which is a caption, not something anyone should be handed as a transcript.
     */
    suspend fun exportEntry(context: Context, entry: DictateHistoryEntry): Boolean {
        val prefs by FlorisPreferenceStore
        if (entry.failed || entry.text.isBlank()) return false
        val treeUri = folderUri(prefs) ?: return false
        val ok = writeLock.withLock { write(context, prefs, treeUri, entry) }
        prefs.dictate.historyExportLastFailure.set(if (ok) 0L else System.currentTimeMillis())
        return ok
    }

    /**
     * Writes every [entries] that is not in the folder yet, and is both the backfill and the way back
     * from a spell of failures.
     *
     * Connecting a folder leaves it empty while the history already holds weeks of dictations, so there
     * has to be something that puts them there; and since a lost write leaves no trace beyond a missing
     * file, the same pass is what repairs it. What is already present is told from the folder listing —
     * the id in the file name is what makes that possible without a column in the table.
     */
    suspend fun exportAll(
        context: Context,
        entries: List<DictateHistoryEntry>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): BulkResult = withContext(Dispatchers.IO) {
        val prefs by FlorisPreferenceStore
        val treeUri = folderUri(prefs) ?: return@withContext BulkResult(0, 0, 0)
        val exportable = entries.filter { !it.failed && it.text.isNotBlank() }
        writeLock.withLock {
            val folder = openFolder(context, treeUri)
                ?: run {
                    // A folder that cannot be listed is the failure this run was meant to repair, so it
                    // has to leave the mark behind rather than reporting a quiet zero.
                    prefs.dictate.historyExportLastFailure.set(System.currentTimeMillis())
                    return@withLock BulkResult(0, 0, exportable.size)
                }
            var written = 0
            var skipped = 0
            var failed = 0
            for ((index, entry) in exportable.withIndex()) {
                ensureActive()
                onProgress(index, exportable.size)
                if ("${fileBaseName(entry)}.txt" in folder.children) {
                    skipped++
                    continue
                }
                if (write(context, prefs, treeUri, entry, folder)) written++ else failed++
            }
            onProgress(exportable.size, exportable.size)
            if (failed > 0) prefs.dictate.historyExportLastFailure.set(System.currentTimeMillis())
            else if (written > 0) prefs.dictate.historyExportLastFailure.set(0L)
            BulkResult(written, skipped, failed)
        }
    }

    /**
     * What one entry is called in the folder: a local, sortable timestamp with the entry id last.
     *
     * [Locale.US] rather than the device locale, deliberately — a locale with its own digits would
     * produce file names no sync client and no script could sort or match.
     */
    fun fileBaseName(entry: DictateHistoryEntry): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(entry.createdAt))
        return "${stamp}_${entry.id}"
    }

    // --- internals ------------------------------------------------------------------------------

    /** The folder's document URI plus a name→id map of what is already in it. */
    private class Folder(val rootUri: Uri, val children: MutableMap<String, String>)

    private fun openFolder(context: Context, treeUri: Uri): Folder? {
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        val rootUri = runCatching { DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId) }
            .getOrNull() ?: return null
        val children = HashMap<String, String>()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        )
        try {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0) ?: continue
                    val id = cursor.getString(1) ?: continue
                    children[name] = id
                }
            } ?: return null
        } catch (e: Exception) {
            // A folder that cannot even be listed cannot be written to either; report it as unavailable
            // rather than creating duplicates against a map we know is wrong.
            flogError { "history export: cannot list folder (${e.message})" }
            return null
        }
        return Folder(rootUri, children)
    }

    private suspend fun write(
        context: Context,
        prefs: FlorisPreferenceModel,
        treeUri: Uri,
        entry: DictateHistoryEntry,
        opened: Folder? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val folder = opened ?: openFolder(context, treeUri) ?: return@withContext false
        val base = fileBaseName(entry)
        val textOk = writeDocument(context, treeUri, folder, "$base.txt", "text/plain") { out ->
            out.write(entry.text.toByteArray(Charsets.UTF_8))
        }
        if (!textOk) return@withContext false
        // The audio is a bonus, never the thing that decides whether the export worked: the transcript is
        // what the folder is for, and a WAV that failed to copy must not make a written transcript look
        // like a failure and be rewritten on the next catch-up.
        if (prefs.dictate.historyExportAudio.get()) {
            val source = entry.audioPath?.let(::File)?.takeIf { it.exists() && it.length() > 0L }
            if (source != null) {
                val container = AudioContainer.of(source)
                val extension = container.extension.ifEmpty { source.extension.lowercase().ifEmpty { "wav" } }
                writeDocument(context, treeUri, folder, "$base.$extension", container.mimeType) { out ->
                    source.inputStream().use { it.copyTo(out) }
                }
            }
        }
        true
    }

    /**
     * Creates (or replaces) one document in the folder.
     *
     * Replacing is not simply "open it and write": truncation (`wt`) is optional in the SAF contract and
     * a provider that ignores it would leave the tail of a longer previous transcript behind the new
     * one. So a short write into an existing document is verified by length and falls back to deleting
     * the document and creating it again.
     */
    private fun writeDocument(
        context: Context,
        treeUri: Uri,
        folder: Folder,
        name: String,
        mime: String,
        body: (java.io.OutputStream) -> Unit,
    ): Boolean {
        val resolver = context.contentResolver
        val existingId = folder.children[name]
        if (existingId != null) {
            val uri = runCatching {
                DocumentsContract.buildDocumentUriUsingTree(treeUri, existingId)
            }.getOrNull()
            if (uri != null) {
                val rewritten = runCatching {
                    resolver.openOutputStream(uri, "wt")?.use(body) ?: return@runCatching false
                    true
                }.getOrDefault(false)
                if (rewritten) return true
                runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            }
        }
        val target = runCatching { DocumentsContract.createDocument(resolver, folder.rootUri, mime, name) }
            .getOrNull()
        if (target == null) {
            flogError { "history export: could not create $name" }
            return false
        }
        val ok = runCatching {
            resolver.openOutputStream(target)?.use(body) ?: return@runCatching false
            true
        }.getOrDefault(false)
        if (!ok) {
            // An empty document left behind would be read as an empty dictation by whatever watches the
            // folder, and would make the next catch-up skip the entry it never actually wrote.
            runCatching { DocumentsContract.deleteDocument(resolver, target) }
            flogError { "history export: could not write $name" }
            return false
        }
        // A provider is free to hand back a different display name than the one asked for (a collision it
        // resolved itself, a suffix it insists on); record what it actually created so a rewrite in the
        // same run finds it rather than creating a second copy.
        folder.children[name] = DocumentsContract.getDocumentId(target)
        return true
    }
}
