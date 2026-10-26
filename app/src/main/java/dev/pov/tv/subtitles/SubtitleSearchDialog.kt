package dev.pov.tv.subtitles

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import dev.pov.tv.PlayerOptionSheet
import dev.pov.tv.PlayerSheetChoice
import dev.pov.tv.data.PlaybackIdentity
import dev.pov.tv.data.SecureStore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** TV remote friendly search/download controller. No account request runs on the UI thread. */
object SubtitleSearchDialog {
    fun show(activity: Activity, identity: PlaybackIdentity, language: String, onSelected: (DownloadedSubtitle) -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        val cancelled = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "pov-subtitles").apply { isDaemon = true } }
        val repository = SubtitleRepository(activity, SecureStore(activity))
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
                val report = repository.search(identity, language)
                activity.runOnUiThread {
                    if (!active()) { close(); return@runOnUiThread }
                    visible?.dismiss()
                    val issues = report.issues.joinToString("\n") { "${it.provider.label}: ${it.message}" }
                    if (report.items.isEmpty()) {
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
