package dev.pov.tv

import android.content.Context
import java.util.Locale

/** Device preferences contain no service credentials. Engine changes apply to the next video. */
class PlaybackPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("pov_playback_preferences", Context.MODE_PRIVATE)

    var selectedEngine: PlaybackEngine
        get() = runCatching { PlaybackEngine.valueOf(preferences.getString("engine", "MEDIA3") ?: "MEDIA3") }
            .getOrDefault(PlaybackEngine.MEDIA3)
        set(value) { check(preferences.edit().putString("engine", value.name).commit()) { "Playback preference could not be saved." } }

    var providerId: String
        get() = runCatching { preferences.getString("provider", "torbox").orEmpty() }.getOrDefault("torbox")
            .takeIf { value -> dev.pov.tv.data.ProviderHub.SPECS.any { it.id == value } } ?: "torbox"
        set(value) {
            require(dev.pov.tv.data.ProviderHub.SPECS.any { it.id == value }) { "Choose a supported playback service." }
            check(preferences.edit().putString("provider", value).commit()) { "Playback service could not be saved." }
        }

    var subtitleLanguage: String
        get() = runCatching { preferences.getString("subtitle_language", "en").orEmpty() }.getOrDefault("en")
            .takeIf { it.matches(Regex("[a-z]{2,3}(?:-[a-z0-9]{2,8})*")) } ?: "en"
        set(value) {
            val language = value.trim().lowercase(Locale.ROOT)
            require(language.matches(Regex("[a-z]{2,3}(?:-[a-z0-9]{2,8})*"))) { "Enter a subtitle language such as en, es, or pt-br." }
            check(preferences.edit().putString("subtitle_language", language).commit()) { "Subtitle preference could not be saved." }
        }
}
