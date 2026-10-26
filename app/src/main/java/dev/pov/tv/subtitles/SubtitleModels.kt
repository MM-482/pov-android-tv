package dev.pov.tv.subtitles

import android.net.Uri
import dev.pov.tv.data.PlaybackIdentity
import dev.pov.tv.data.SecureStore
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

enum class SubtitleProvider(val label: String) {
    OPEN_SUBTITLES("OpenSubtitles"), SUBDL("SubDL"), SUBSOURCE("SubSource")
}

data class SubtitleResult(
    val provider: SubtitleProvider,
    val id: String,
    val displayName: String,
    val language: String,
    val identity: PlaybackIdentity,
    val fileName: String = "",
    val hearingImpaired: Boolean = false,
    val downloadUrl: String = ""
)
data class SubtitleProviderIssue(val provider: SubtitleProvider, val message: String)
data class SubtitleSearchReport(val items: List<SubtitleResult>, val issues: List<SubtitleProviderIssue>)
data class DownloadedSubtitle(val uri: Uri, val displayName: String, val language: String, val mimeType: String = "application/x-subrip")

class SubtitleException(message: String, val status: Int = 0, val retryAfterSeconds: Int = 0) : IOException(message)

/** Only credentials belong here. PlaybackPreferences owns the preferred subtitle language. */
object SubtitleSettings {
    const val OPEN_SUBTITLES_API_KEY = "subtitles.opensubtitles.api_key"
    const val OPEN_SUBTITLES_USERNAME = "subtitles.opensubtitles.username"
    const val OPEN_SUBTITLES_PASSWORD = "subtitles.opensubtitles.password"
    const val SUBDL_API_KEY = "subtitles.subdl.api_key"
    const val SUBSOURCE_API_KEY = "subtitles.subsource.api_key"
    internal const val OPEN_SUBTITLES_REVISION = "subtitles.opensubtitles.config_revision"
    internal val credentialsLock = Any()
    private val openConfigKeys = setOf(OPEN_SUBTITLES_API_KEY, OPEN_SUBTITLES_USERNAME, OPEN_SUBTITLES_PASSWORD)
    private val openAuthKeys = listOf("token", "base_url", "auth_fingerprint", "expires_ms", "rejected_fingerprint")
        .map { "subtitles.opensubtitles.$it" }

    /** All settings writers and the login CAS share this lock; replacements invalidate cached auth. */
    fun saveCredentials(store: SecureStore, updates: Map<String, String>) = synchronized(credentialsLock) {
        require(updates.keys.all { it in openConfigKeys || it == SUBDL_API_KEY || it == SUBSOURCE_API_KEY }) { "Invalid subtitle setting." }
        store.putAll(if (updates.keys.any { it in openConfigKeys }) updates + resetOpenAuth() else updates)
    }

    fun clearConnection(store: SecureStore, provider: SubtitleProvider) = synchronized(credentialsLock) {
        val updates = when (provider) {
            SubtitleProvider.OPEN_SUBTITLES -> openConfigKeys.associateWith { "" } + resetOpenAuth()
            SubtitleProvider.SUBDL -> mapOf(SUBDL_API_KEY to "")
            SubtitleProvider.SUBSOURCE -> mapOf(SUBSOURCE_API_KEY to "")
        }
        store.putAll(updates)
    }

    private fun resetOpenAuth(): Map<String, String> = openAuthKeys.associateWith { "" } +
        (OPEN_SUBTITLES_REVISION to UUID.randomUUID().toString())

    fun language(code: String): String {
        val value = code.trim().lowercase(Locale.ROOT).replace('_', '-')
        require(value.matches(Regex("[a-z]{2,3}(?:-[a-z0-9]{2,8})*"))) { "Choose a valid subtitle language code, such as en or es." }
        return value
    }
}

internal interface SubtitleCredentials {
    fun get(key: String): String
    fun openSnapshot(): OpenSubtitlesSnapshot
    fun putIfOpenUnchanged(expected: OpenSubtitlesSnapshot, values: Map<String, String>): Boolean
}
internal class StoredSubtitleCredentials(private val store: SecureStore) : SubtitleCredentials {
    override fun get(key: String) = store.get(key)
    override fun openSnapshot() = synchronized(SubtitleSettings.credentialsLock) { OpenSubtitlesSnapshot.read(::get) }
    override fun putIfOpenUnchanged(expected: OpenSubtitlesSnapshot, values: Map<String, String>): Boolean = synchronized(SubtitleSettings.credentialsLock) {
        if (OpenSubtitlesSnapshot.read(::get) != expected) false else { store.putAll(values); true }
    }
}

internal data class OpenSubtitlesSnapshot(val apiKey: String, val username: String, val password: String, val revision: String) {
    val fingerprint: String get() = MessageDigest.getInstance("SHA-256").digest(
        listOf(apiKey, username, password).joinToString("\u0000").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    companion object {
        fun read(get: (String) -> String) = OpenSubtitlesSnapshot(get(SubtitleSettings.OPEN_SUBTITLES_API_KEY),
            get(SubtitleSettings.OPEN_SUBTITLES_USERNAME), get(SubtitleSettings.OPEN_SUBTITLES_PASSWORD), get(SubtitleSettings.OPEN_SUBTITLES_REVISION))
    }
}
