package dev.pov.tv.subtitles

import android.content.Context
import android.net.Uri
import dev.pov.tv.data.AddonRepository
import dev.pov.tv.data.CameoAddonSubtitle
import dev.pov.tv.data.CameoAddon
import dev.pov.tv.data.CameoServiceUrls
import dev.pov.tv.data.LiveCameoServiceTransport
import dev.pov.tv.data.PlaybackIdentity
import dev.pov.tv.data.SecureStore
import java.io.File
import java.util.UUID

/** Add-on downloads are bounded and credential-free; cached names never come from archive paths. */
internal class AddonSubtitleDownloads(private val context: Context,
    private val addons: () -> List<CameoAddon> = { AddonRepository(SecureStore(context)).list() },
    private val fetch: (String, String, Int) -> ByteArray = LiveCameoServiceTransport::publicBytes,
) {
    fun download(result: CameoAddonSubtitle, identity: PlaybackIdentity): DownloadedSubtitle {
        val addon = addons().singleOrNull { it.id == result.addonId && it.enabled }
            ?: throw SubtitleException("This subtitle add-on was removed or disabled.")
        val uri = CameoServiceUrls.addonResource(result.url, addon.url)
        val bytes = fetch(uri.toString(), CameoServiceUrls.origin(addon.url), 8 * 1024 * 1024)
        val suggested = uri.path.substringAfterLast('/').takeIf { Regex("(?i)\\.(srt|vtt|ass|ssa|zip|gz)$").containsMatchIn(it) }.orEmpty()
        val file = SubtitleArchive.read(bytes, suggested, identity)
        check(addons().any { it.id == addon.id && it.url == addon.url && it.enabled }) { "This subtitle add-on was removed or disabled." }
        val directory = File(context.cacheDir, "downloaded-subtitles")
        synchronized(LOCK) {
            check(directory.isDirectory || directory.mkdirs()) { "The subtitle cache could not be created." }
            directory.listFiles()?.filter { it.name.startsWith("cameo-addon-sub-") }?.sortedByDescending { it.lastModified() }
                ?.drop(39)?.forEach { it.delete() }
            val destination = File(directory, "cameo-addon-sub-${UUID.randomUUID()}.${file.extension}")
            try { destination.writeBytes(file.bytes) } catch (error: Exception) { destination.delete(); throw error }
            return DownloadedSubtitle(Uri.fromFile(destination), result.name, result.language, file.mimeType)
        }
    }
    companion object { private val LOCK = Any() }
}
