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

/** Deliberate native-view renders of controlled metadata. Never a physical-TV or pixel-match claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CameoTvScreenshotTest {
    @Test fun `export five TV reference viewport renders only when explicitly requested`() {
        val requested = System.getProperty("cameo.capture.dir").orEmpty()
        assumeTrue("Set cameo.capture.dir to opt in to native fixture screenshots.", requested.isNotBlank())
        val destination = File(requested, "tv"); require(destination.isAbsolute)
        check(destination.mkdirs() || destination.isDirectory)
        val artDirectory = File(System.getProperty("cameo.capture.art.dir").orEmpty())
        require(artDirectory.isAbsolute && artDirectory.isDirectory) { "Set cameo.capture.art.dir to unchanged Windows ui/assets." }
        val bitmaps = mutableMapOf<String, Bitmap>(); val metadata = JSONArray()
        fun source(name: String) = "https://public-fixture.example/$name"
        val movies = listOf(
            MediaItemData("tt0816692", "movie", "Interstellar", "2014", source("tt0816692.jpg"), source("tt0816692-backdrop.jpg"), genres = listOf("Sci-Fi"), runtime = "2h 49m"),
            MediaItemData("tt15239678", "movie", "Dune: Part Two", "2024", source("tt15239678.jpg"), source("tt15239678-backdrop.jpg")),
            MediaItemData("tt1877830", "movie", "The Batman", "2022", source("tt1877830.jpg"), source("tt1877830-backdrop.jpg")),
            MediaItemData("tt2543164", "movie", "Arrival", "2016", source("tt2543164.jpg"), source("tt2543164-backdrop.jpg")),
            MediaItemData("tt2278388", "movie", "The Grand Budapest Hotel", "2014", source("tt2278388.jpg")),
            MediaItemData("tt1856101", "movie", "Blade Runner 2049", "2017", source("tt1856101.jpg")))
        val permittedArt = movies.flatMap { listOf(it.poster, it.background) }.filter { it.isNotBlank() }.map { it.substringAfterLast('/') }.toSet()
        val recent = listOf(movies[2], movies[1], movies[3]).mapIndexed { index, item -> PlaybackStore.Recent("public-fixture:$index", item.title,
            PlaybackIdentity(item.imdbId, item.type, item.title), (index + 1) * 1_440_000L, 10_560_000) }
        try {
            ThemeSpec.cameoTvThemes.forEachIndexed { index, theme ->
                val controller = Robolectric.buildActivity(Activity::class.java)
                controller.get().setTheme(android.R.style.Theme_Material_NoActionBar); controller.setup()
                try {
                    val activity = controller.get()
                    val targets = listOf("home" to "Home", "movie" to "Movies", "series" to "Shows", "anime" to "Anime", "library" to "Library", "search" to "Search", "settings" to "Settings").map { ConceptDestination(it.first, it.second) {} }
                    val renderer = CameoTvRenderer(activity, theme); val shell = renderer.shell(targets) { "home" }
                    val callbacks = ConceptHomeCallbacks({ view, address ->
                        if (address.isNotBlank()) {
                            val name = address.substringAfterLast('/'); require(name in permittedArt)
                            view.setImageBitmap(bitmaps.getOrPut(name) { BitmapFactory.decodeFile(File(artDirectory, name).absolutePath)
                                ?: throw IllegalStateException("Fixture artwork cannot be decoded: $name") })
                        }
                    }, {}, {}, {}, {}, { _, _ -> }, {})
                    renderer.home(shell.body, shell.heroBackdrop, shell.backdropArt, ConceptHomeData(movies, emptyList(), recent), callbacks) {}
                    activity.setContentView(shell.root); shadowOf(Looper.getMainLooper()).idle()
                    repeat(2) {
                        shell.root.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY))
                        shell.root.layout(0, 0, 960, 540); shadowOf(Looper.getMainLooper()).idle()
                    }
                    val bitmap = Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888)
                    try {
                        shell.root.draw(Canvas(bitmap)); val colors = mutableSetOf<Int>()
                        for (y in 0 until 540 step 17) for (x in 0 until 960 step 13) colors += bitmap.getPixel(x, y)
                        assertTrue("TV native capture must contain artwork and controls.", colors.size > 30)
                        val name = "%02d-%s-native-960x540.png".format(index + 1, theme.id.removePrefix("cameo-"))
                        File(destination, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        metadata.put(JSONObject().put("id", "%02d".format(index + 1)).put("theme", theme.displayName).put("file", name)
                            .put("width", 960).put("height", 540).put("density", "mdpi").put("physicalDevice", false).put("visualMatchVerified", false)
                            .put("source", "Android native views rendered by Robolectric native graphics")
                            .put("content", "Controlled public film metadata, unchanged source JPGs and sample progress; no connected accounts"))
                    } finally { bitmap.recycle() }
                } finally { controller.pause().stop().destroy() }
            }
        } finally { bitmaps.values.forEach { it.recycle() } }
        assertEquals(5, metadata.length())
        File(destination, "tv-native-captures.json").writeText(JSONObject().put("title", "Cameo TV native test renders")
            .put("scope", "Controlled app-canvas renders, not physical TV screenshots or pixel-equivalence certification").put("captures", metadata).toString(2))
    }
}
