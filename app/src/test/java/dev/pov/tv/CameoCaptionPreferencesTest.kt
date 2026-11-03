package dev.pov.tv

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CameoCaptionPreferencesTest {
    @Test fun `caption defaults survive reopening without replacing existing playback settings`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("pov_playback_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        val first = PlaybackPreferences(context)
        first.selectedEngine = PlaybackEngine.VLC; first.subtitleLanguage = "es"
        val desired = CaptionAppearance(CaptionSize.LARGE, CaptionColor.YELLOW, CaptionBackground.SOFT_BLACK, CaptionPosition.RAISED)
        first.captionAppearance = desired
        val restored = PlaybackPreferences(context)
        assertEquals(desired, restored.captionAppearance)
        assertEquals(PlaybackEngine.VLC, restored.selectedEngine)
        assertEquals("es", restored.subtitleLanguage)
    }

    @Test fun `corrupt or future caption values restore defaults instead of breaking playback`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("pov_playback_preferences", Context.MODE_PRIVATE).edit().clear()
            .putInt("caption_size", 32).putString("caption_color", "REMOVED_COLOR")
            .putBoolean("caption_background", true).putString("caption_position", "INVALID").commit()
        assertEquals(CaptionAppearance(), PlaybackPreferences(context).captionAppearance)
    }
}
