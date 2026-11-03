package dev.pov.tv.subtitles

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import dev.pov.tv.PlayerOptionSheet
import dev.pov.tv.PlayerSheetChoice
import dev.pov.tv.data.PlaybackIdentity
import dev.pov.tv.data.SecureStore
import dev.pov.tv.data.AddonRepository
import dev.pov.tv.data.CameoConnections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** TV remote friendly search/download controller. No account request runs on the UI thread. */
object SubtitleSearchDialog {
    fun show(activity: Activity, identity: PlaybackIdentity, language: String, onSelected: (DownloadedSubtitle) -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        val accounts = try { CameoConnections(SecureStore(activity)).list().filter { it.kind in CameoConnections.SUBTITLE_KINDS } }
        catch (_: Exception) {
            AlertDialog.Builder(activity).setTitle("Subtitles").setMessage("Saved subtitle accounts could not be read. Check Connections before searching.")
                .setPositiveButton("Done", null).show(); return
        }
        if (accounts.isEmpty()) { search(activity, identity, language, null, onSelected); return }
        val rows = listOf(PlayerSheetChoice("Existing accounts and enabled add-ons", action = {
            search(activity, identity, language, null, onSelected)
        })) + accounts.map { account -> PlayerSheetChoice("${account.name.ifBlank { account.kind }} · ${account.kind}", action = {
            search(activity, identity, language, account.id, onSelected)
        }) }
        PlayerOptionSheet.create(activity, "Choose subtitle account", "Search and download with the selected account.", rows, 0xFF65D7F1.toInt()).show()
    }
    fun showForConnection(activity: Activity, identity: PlaybackIdentity, language: String, connectionId: String,
        onSelected: (DownloadedSubtitle) -> Unit) = search(activity, identity, language, connectionId, onSelected)

    private fun search(activity: Activity, identity: PlaybackIdentity, language: String, connectionId: String?, onSelected: (DownloadedSubtitle) -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        val cancelled = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "pov-subtitles").apply { isDaemon = true } }
        val repository = SubtitleRepository(activity, SecureStore(activity))
        val connections = CameoConnections(SecureStore(activity))
        var visible: Dialog? = null
        var downloadJob: Thread? = null
        fun close() { cancelled.set(true); executor.shutdownNow(); downloadJob?.interrupt() }
        fun active() = !cancelled.get() && !activity.isFinishing && !activity.isDestroyed
        fun progress(message: String) {
            visible?.dismiss()
            visible = AlertDialog.Builder(activity).setTitle("Subtitles").setMessage(message)
                .setNegativeButton("Cancel") { _, _ -> close() }.create().apply { setOnCancelListener { close() }; show() }
        }
        fun error(message: String) {
            visible?.dismiss()
            visible = AlertDialog.Builder(activity).setTitle("Subtitles").setMessage(message)
                .setPositiveButton("Done") { _, _ -> close() }.create().apply { setOnCancelListener { close() }; show() }
        }
        progress("Searching ${language.uppercase()} subtitles for ${identity.title}…")
        executor.execute {
            try {
                val savedResults = if (connectionId != null) connections.searchSubtitles(connectionId, identity, language) else emptyList()
                val report = if (connectionId == null && identity.imdbId.matches(Regex("tt[0-9]{5,12}"))) repository.search(identity, language)
                    else SubtitleSearchReport(emptyList(), emptyList())
                var addonIssue = ""
                val addonResults = if (connectionId != null) emptyList() else try {
                    val aliases = setOf(language.lowercase(), language.substringBefore('-').lowercase(),
                        mapOf("en" to "eng", "es" to "spa", "fr" to "fra", "de" to "deu", "it" to "ita", "pt" to "por", "ja" to "jpn", "ko" to "kor", "zh" to "zho")[language.substringBefore('-')].orEmpty())
                    AddonRepository(SecureStore(activity)).subtitles(identity).filter { it.language.isBlank() || it.language.lowercase() in aliases }
                } catch (_: Exception) { addonIssue = "An installed add-on could not return subtitles."; emptyList() }
                activity.runOnUiThread {
                    if (!active()) { close(); return@runOnUiThread }
                    visible?.dismiss()
                    val issues = (report.issues.map { "${it.provider.label}: ${it.message}" } + addonIssue).filter { it.isNotBlank() }.joinToString("\n")
                    if (report.items.isEmpty() && addonResults.isEmpty() && savedResults.isEmpty()) {
                        error(listOf("No matching subtitles were found for this title and language.", issues).filter { it.isNotBlank() }.joinToString("\n\n"))
                    } else {
                        val rows = report.items.map { result -> PlayerSheetChoice(
                            "${result.provider.label} · ${result.language.uppercase()}${if (result.hearingImpaired) " · Hearing impaired" else ""}\n${result.displayName}",
                            action = {
                                progress("Downloading subtitle from ${result.provider.label}…")
                                downloadJob = Thread({
                                    try {
                                        val downloaded = repository.download(result)
                                        activity.runOnUiThread { if (active()) { visible?.dismiss(); onSelected(downloaded) }; close() }
                                    } catch (failure: Exception) {
                                        activity.runOnUiThread { if (active()) error((failure as? SubtitleException)?.message ?: "The subtitle could not be downloaded. Try another result.") else close() }
                                    }
                                }, "pov-subtitle-download").apply { isDaemon = true; start() }
                            }
                        ) } + savedResults.map { result -> PlayerSheetChoice(
                            "${result.accountName} · ${result.result.language.uppercase()}\n${result.result.displayName}",
                            action = {
                                progress("Downloading subtitle from ${result.accountName}…")
                                downloadJob = Thread({
                                    try {
                                        val downloaded = connections.downloadSubtitle(result.connectionId, result, activity)
                                        activity.runOnUiThread { if (active()) { visible?.dismiss(); onSelected(downloaded) }; close() }
                                    } catch (failure: Exception) {
                                        activity.runOnUiThread { if (active()) error((failure as? SubtitleException)?.message ?: "The selected account could not download this subtitle. Search again or check the connection.") else close() }
                                    }
                                }, "cameo-account-subtitle-download").apply { isDaemon = true; start() }
                            }
                        ) } + addonResults.map { result -> PlayerSheetChoice(
                            "Add-on · ${result.language.uppercase().ifBlank { "Language not specified" }}\n${result.name}",
                            action = {
                                progress("Downloading add-on subtitles…")
                                downloadJob = Thread({
                                    try {
                                        val downloaded = AddonSubtitleDownloads(activity).download(result, identity)
                                        activity.runOnUiThread { if (active()) { visible?.dismiss(); onSelected(downloaded) }; close() }
                                    } catch (failure: Exception) {
                                        activity.runOnUiThread { if (active()) error((failure as? SubtitleException)?.message ?: "The add-on subtitle could not be downloaded. Try another result.") else close() }
                                    }
                                }, "cameo-addon-subtitle-download").apply { isDaemon = true; start() }
                            }
                        ) }
                        visible = PlayerOptionSheet.create(activity, "Download subtitles", "Select a release matching your video. Provider download quotas apply." + if (issues.isNotBlank()) "\n$issues" else "", rows, 0xFF65D7F1.toInt()).apply {
                            setOnCancelListener { close() }; show()
                        }
                    }
                }
            } catch (failure: Exception) {
                activity.runOnUiThread { if (active()) error((failure as? SubtitleException)?.message ?: "Subtitle search could not be completed.") else close() }
            } finally { executor.shutdown() }
        }
    }
}
