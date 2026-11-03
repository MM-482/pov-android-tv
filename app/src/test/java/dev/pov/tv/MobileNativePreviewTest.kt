package dev.pov.tv

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.view.View
import dev.pov.tv.data.MediaItemData
import dev.pov.tv.data.PlaybackIdentity
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.Locale

/** Opt-in simulated native renders, with synthetic artwork and no account, network, or device capture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class MobileNativePreviewTest {
    @Test fun `render five appearances in cover and unfolded sized windows when explicitly requested`() {
        assumeTrue("Set CAMEO_RENDER_PREVIEWS=1 for optional native visual QA", System.getenv("CAMEO_RENDER_PREVIEWS") == "1")
        val directory = File("build/reports/cameo-native-previews").apply { mkdirs() }
        val sizes = listOf(Triple("w360dp-h800dp-port-mdpi", 360, 800), Triple("w720dp-h690dp-land-mdpi", 720, 690))
        for ((qualifiers, width, height) in sizes) {
            RuntimeEnvironment.setQualifiers(qualifiers)
            for (theme in ThemeSpec.selectableThemes) {
                val controller = Robolectric.buildActivity(Activity::class.java).setup()
                try {
                    val activity = controller.get().apply { setTheme(R.style.AppTheme) }
                    val destinations = linkedMapOf("home" to "Home", "movie" to "Movies", "series" to "Shows", "anime" to "Anime",
                        "library" to "Library", "search" to "Search", "settings" to "Settings")
                    val shell = MobileShellRenderer(activity, theme).render(destinations.map { (key, label) -> ConceptDestination(key, label) {} }) { "home" }
                    val movies = listOf("The Last Observatory", "After the Rain", "Across the River", "The Quiet City").mapIndexed { index, title ->
                        MediaItemData("tt${1_000_000 + index}", "movie", title, "2026", background = "fixture:$index",
                            description = "At a remote mountain observatory, two old friends follow a signal that changes the course of one unforgettable night.",
                            genres = listOf("Drama", "Adventure"), runtime = "108 min")
                    }
                    val series = MediaItemData("tt2000000", "series", "Northern Lights", "2026", background = "fixture:4")
                    val identity = PlaybackIdentity(series.imdbId, "series", series.title, 2, 3)
                    val recent = listOf(PlaybackStore.Recent("synthetic-resume", movies[1].title,
                        PlaybackIdentity(movies[1].imdbId, "movie", movies[1].title), 1_800_000, 6_480_000))
                    val artwork = listOf(0xFF3A5778.toInt(), 0xFF72534C.toInt(), 0xFF41635E.toInt(), 0xFF645879.toInt(), 0xFF456578.toInt())
                    MobileHomeRenderer(activity, theme).render(shell.body, shell.heroBackdrop, shell.backdropArt,
                        ConceptHomeData(movies, listOf(series), recent, listOf(identity)), ConceptHomeCallbacks(
                            loadImage = { view, address ->
                                val tint = artwork[address.substringAfterLast(':').toIntOrNull()?.mod(artwork.size) ?: 0]
                                view.setImageDrawable(GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(tint, 0xFF111522.toInt())))
                            }, details = {}, play = {}, resume = {}, browse = {}, bindContinue = { _, _ -> }, episode = {}))
                    activity.setContentView(shell.root)
                    repeat(2) {
                        shell.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                        shell.root.layout(0, 0, width, height)
                        shadowOf(Looper.getMainLooper()).idle()
                    }
                    assertTrue(shell.body.measuredHeight > 0)
                    val bitmap = Bitmap.createBitmap(width, height + 34, Bitmap.Config.ARGB_8888)
                    try {
                        val canvas = Canvas(bitmap)
                        shell.root.draw(canvas)
                        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
                        canvas.drawRect(0f, height.toFloat(), width.toFloat(), (height + 34).toFloat(), label)
                        label.color = Color.rgb(34, 40, 48); label.textSize = 11f
                        label.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                        canvas.drawText("SIMULATED NATIVE LAYOUT · ${theme.displayName} · ${width}dp", 10f, height + 21f, label)
                        val output = File(directory, "simulated-${theme.displayName.lowercase(Locale.ROOT)}-${width}dp.png")
                        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        assertTrue(output.length() > 1_000)
                    } finally { bitmap.recycle() }
                } finally { controller.pause().stop().destroy(); shadowOf(Looper.getMainLooper()).idle() }
            }
        }
    }
}
