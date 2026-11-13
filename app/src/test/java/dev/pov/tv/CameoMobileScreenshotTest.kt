package dev.pov.tv

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import dev.pov.tv.data.MediaItemData
import dev.pov.tv.data.PlaybackIdentity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

/** Opt-in native-view captures. These are controlled test renders, never physical-phone evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w390dp-h891dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CameoMobileScreenshotTest {
    @Test fun `export five mobile reference viewport renders only when explicitly requested`() {
        val requested = System.getProperty("cameo.capture.dir").orEmpty()
        assumeTrue("Set cameo.capture.dir to opt in to native fixture screenshots.", requested.isNotBlank())
        val destination = File(requested)
        require(destination.isAbsolute) { "Use an absolute native-capture output directory." }
        check(destination.mkdirs() || destination.isDirectory)
        val suppliedArt = System.getProperty("cameo.capture.art.dir").orEmpty()
        val artDirectory = if (suppliedArt.isNotBlank()) File(suppliedArt) else listOf(
            File("inspection/cameo-windows-0.2.4-reference/ui/assets"),
            File("../inspection/cameo-windows-0.2.4-reference/ui/assets")).firstOrNull { it.isDirectory }
            ?: throw IllegalStateException("Set cameo.capture.art.dir to the unchanged source Windows ui/assets folder.")
        val metadata = JSONArray(); val bitmaps = mutableMapOf<String, Bitmap>()
        val sourceFiles = setOf("tt0816692.jpg", "tt0816692-backdrop.jpg", "tt15239678.jpg", "tt15239678-backdrop.jpg",
            "tt1877830.jpg", "tt1877830-backdrop.jpg", "tt2543164.jpg", "tt2543164-backdrop.jpg", "tt2278388.jpg", "tt1856101.jpg")
        fun source(name: String) = "https://public-fixture.example/$name"
        val movies = listOf(
            MediaItemData("tt0816692", "movie", "Interstellar", "2014", source("tt0816692.jpg"), source("tt0816692-backdrop.jpg"),
                genres = listOf("Sci-Fi"), runtime = "2h 49m"),
            MediaItemData("tt15239678", "movie", "Dune: Part Two", "2024", source("tt15239678.jpg"), source("tt15239678-backdrop.jpg")),
            MediaItemData("tt1877830", "movie", "The Batman", "2022", source("tt1877830.jpg"), source("tt1877830-backdrop.jpg")),
            MediaItemData("tt2543164", "movie", "Arrival", "2016", source("tt2543164.jpg"), source("tt2543164-backdrop.jpg")),
            MediaItemData("tt2278388", "movie", "The Grand Budapest Hotel", "2014", source("tt2278388.jpg")),
            MediaItemData("tt1856101", "movie", "Blade Runner 2049", "2017", source("tt1856101.jpg")))
        val recent = listOf(
            PlaybackStore.Recent("public-fixture:batman", "The Batman", PlaybackIdentity("tt1877830", "movie", "The Batman"), 1_440_000, 10_560_000),
            PlaybackStore.Recent("public-fixture:dune", "Dune: Part Two", PlaybackIdentity("tt15239678", "movie", "Dune: Part Two"), 2_880_000, 9_960_000))
        try {
            MobileCameoTheme.entries.forEachIndexed { index, theme ->
                val controller = Robolectric.buildActivity(Activity::class.java)
                controller.get().setTheme(android.R.style.Theme_Material_NoActionBar)
                controller.setup()
                try {
                    val activity = controller.get()
                    val destinations = listOf("home", "movie", "series", "anime", "library", "search", "settings").map { key -> ConceptDestination(key, key) {} }
                    val renderer = MobileCameoRenderer(activity, theme)
                    val shell = renderer.shell(destinations) { "home" }
                    val callbacks = ConceptHomeCallbacks(loadImage = { view, address ->
                        if (address.isNotBlank()) {
                            val name = address.substringAfterLast('/')
                            require(name in sourceFiles) { "Capture attempted an unapproved art source." }
                            val bitmap = bitmaps.getOrPut(name) {
                                BitmapFactory.decodeFile(File(artDirectory, name).absolutePath)
                                    ?: throw IllegalStateException("Public fixture artwork could not be decoded: $name")
                            }
                            view.setImageBitmap(bitmap)
                        }
                    }, details = {}, play = {}, resume = {}, browse = {}, bindContinue = { _, _ -> }, episode = {})
                    renderer.home(shell.body, ConceptHomeData(movies, emptyList(), recent), callbacks) {}
                    activity.setContentView(shell.root)
                    shadowOf(Looper.getMainLooper()).idle()
                    val width = 390; val height = if (theme == MobileCameoTheme.GLASS) 844 else 891
                    shell.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    shell.root.layout(0, 0, width, height)
                    shadowOf(Looper.getMainLooper()).idle()
                    shell.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    shell.root.layout(0, 0, width, height)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    try {
                        shell.root.draw(Canvas(bitmap))
                        val colors = mutableSetOf<Int>()
                        for (y in 0 until height step 17) for (x in 0 until width step 13) colors += bitmap.getPixel(x, y)
                        assertTrue("Native fixture capture must contain rendered artwork and controls, not an empty canvas.", colors.size > 30)
                        val filename = "%02d-%s-native-%dx%d.png".format(index + 1, theme.name.lowercase(java.util.Locale.ROOT), width, height)
                        File(destination, filename).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        metadata.put(JSONObject().put("id", "%02d".format(index + 1)).put("theme", theme.title).put("file", filename)
                            .put("width", width).put("height", height).put("density", "mdpi")
                            .put("source", "Real Android native views rendered by Robolectric native graphics")
                            .put("physicalDevice", false).put("content", "Controlled public film metadata, unchanged source JPGs and sample progress; no connected accounts")
                            .put("visualMatchVerified", false))
                    } finally { bitmap.recycle() }
                } finally { controller.pause().stop().destroy() }
            }
        } finally { bitmaps.values.forEach { it.recycle() } }
        assertEquals(5, metadata.length())
        File(destination, "mobile-native-captures.json").writeText(JSONObject().put("title", "Cameo mobile native test renders")
            .put("scope", "Controlled app-canvas renders at provisional phone viewports; not physical device screenshots or a pixel-equivalence certification")
            .put("captures", metadata).toString(2))
    }
}
