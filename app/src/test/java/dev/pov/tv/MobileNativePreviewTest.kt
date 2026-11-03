package dev.pov.tv

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.TextView
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/** Opt-in native views, offline fixture media, no accounts/network/physical-device capture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w426dp-h923dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class MobileNativePreviewTest {
    private data class Viewport(val family: String, val width: Int, val height: Int, val density: Float,
        val topInsetDp: Int = 30, val bottomInsetDp: Int = 20) {
        val tv get() = family == "tv"
        val widthDp get() = (width / density).roundToInt()
        val heightDp get() = (height / density).roundToInt()
        val qualifiers get() = "w${widthDp}dp-h${heightDp}dp-" + (if (width > height) "land" else "port") +
            (if (tv) "-television" else "") + (if (density == 2f) "-xhdpi" else "-mdpi")
        val topInset get() = (topInsetDp * density).roundToInt()
        val bottomInset get() = (bottomInsetDp * density).roundToInt()
    }

    @Test fun `render five reference based phone fold and TV appearances when explicitly requested`() {
        assumeTrue("Set CAMEO_RENDER_PREVIEWS=1 for optional native visual QA", System.getenv("CAMEO_RENDER_PREVIEWS") == "1")
        val directory = File("build/reports/cameo-native-previews").apply { mkdirs() }
        val supplied = System.getenv("CAMEO_CONCEPT_REFERENCE_DIR")?.takeIf(String::isNotBlank)?.let(::File)
        if (supplied != null) assertTrue("The supplied concept directory must exist", supplied.isDirectory)
        val manifest = JSONObject().put("schema_version", 1)
            .put("status", "SIMULATED NATIVE ANDROID UI; not a physical-device capture or pixel-match certification")
            .put("artwork", "Optional text-free reference artwork samples. Navigation, titles, controls, shelves, and progress are native Android views.")
            .put("system_chrome", "Phone/Fold reserve representative system-bar space. OS clock, status icons, and gesture UI are not fabricated.")
            .put("capture_pipeline", "Robolectric API 28 native graphics; measured production Android views drawn to a software bitmap Canvas")
            .put("render_limitations", "Artwork path clipping is part of production views and is captured. GPU compositing and elevation shadows may differ from this software Canvas. Physical-device rendering remains unverified.")
            .put("reference_directory_supplied", supplied != null)
        val renders = JSONArray()
        val failures = mutableListOf<String>()
        ConceptPreviewArtwork(supplied).use { art ->
            for (theme in ThemeSpec.selectableThemes) {
                val phone = if (theme == ThemeSpec.GLASS) Viewport("phone", 852, 1846, 2f) else Viewport("phone", 830, 1896, 2f)
                for (viewport in listOf(phone, Viewport("fold", 720, 800, 1f), Viewport("tv", 1672, 941, 1f, 0, 0))) {
                    renders.put(render(directory, theme, viewport, art, failures))
                }
            }
            manifest.put("artwork_sources", art.sourceRecords())
        }
        manifest.put("renders", renders).put("geometry_failures", JSONArray(failures))
        File(directory, "native-preview-metadata.json").writeText(manifest.toString(2) + "\n")
        assertEquals("Five themes in three native form factors", 15, renders.length())
        assertTrue("Native geometry checks: ${failures.joinToString("; ")}", failures.isEmpty())
    }

    @Suppress("DEPRECATION")
    private fun render(directory: File, theme: ThemeSpec, viewport: Viewport, art: ConceptPreviewArtwork,
        failures: MutableList<String>): JSONObject {
        RuntimeEnvironment.setQualifiers(viewport.qualifiers)
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val activity = controller.get().apply { setTheme(R.style.AppTheme) }
            activity.resources.displayMetrics.apply {
                widthPixels = viewport.width; heightPixels = viewport.height
                density = viewport.density; scaledDensity = viewport.density
                densityDpi = (160 * viewport.density).roundToInt()
            }
            activity.resources.configuration.apply { screenWidthDp = viewport.widthDp; screenHeightDp = viewport.heightDp }
            if (viewport.tv) activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            val nav = linkedMapOf("home" to "Home", "movie" to "Movies", "series" to "Shows", "anime" to "Anime",
                "library" to "Library", "search" to "Search", "settings" to "Settings")
                .map { (key, label) -> ConceptDestination(key, label) {} }
            val shell = if (viewport.tv) ConceptShellRenderer(activity, theme).render(nav) { "home" }
                else MobileShellRenderer(activity, theme).render(nav) { "home" }
            val data = sampleData(viewport.tv)
            val explored = mutableListOf<MediaItemData>()
            val saved = mutableListOf<MediaItemData>()
            val callbacks = ConceptHomeCallbacks(loadImage = { view, address -> view.setImageDrawable(art.drawable(theme, address, viewport.family)) },
                details = { explored += it }, play = {}, resume = {}, browse = {}, bindContinue = { _, _ -> },
                episode = {}, saveToLibrary = { saved += it })
            if (viewport.tv) ConceptHomeRenderer(activity, theme).render(shell.body, shell.heroBackdrop, shell.backdropArt, data, callbacks)
            else MobileHomeRenderer(activity, theme).render(shell.body, shell.heroBackdrop, shell.backdropArt, data, callbacks)
            activity.setContentView(shell.root)
            shadowOf(Looper.getMainLooper()).idle()
            repeat(3) { pass ->
                if (!viewport.tv) {
                    val insets = ReflectionHelpers.callConstructor(WindowInsets::class.java,
                        ReflectionHelpers.ClassParameter.from(Rect::class.java, Rect(0, viewport.topInset, 0, viewport.bottomInset)))
                    shell.root.dispatchApplyWindowInsets(insets)
                    // This fixture reserves the chosen system-bar geometry; it does not simulate SystemUI.
                    shell.root.setPadding(0, viewport.topInset, 0, viewport.bottomInset)
                }
                shell.root.measure(View.MeasureSpec.makeMeasureSpec(viewport.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(viewport.height, View.MeasureSpec.EXACTLY))
                shell.root.layout(0, 0, viewport.width, viewport.height)
                if (pass < 2) shadowOf(Looper.getMainLooper()).idle()
            }
            val primaryTag = if (viewport.tv) "concept:hero:explore" else "mobile:hero:primary"
            val libraryTag = if (viewport.tv) "concept:hero:library" else "mobile:hero:library"
            if (viewport.tv) descendants(shell.root).firstOrNull { it.tag == primaryTag }?.requestFocus()
            val filename = "native-${theme.displayName.lowercase(Locale.ROOT)}-${viewport.family}.png"
            saveImage(File(directory, filename), shell.root, theme, viewport, art.usesReferenceArtwork)
            var geometry = JSONObject()
            var error: String? = null
            try {
                val primary = target(shell.root, primaryTag)
                val library = target(shell.root, libraryTag)
                assertInside(shell.root, primary, "Explore"); assertInside(shell.root, library, "Library")
                assertTrue(primary.isClickable && library.isClickable)
                assertTrue(primary.performClick()); assertTrue(library.performClick())
                assertSame("Explore preserves title identity", data.movies.first(), explored.single())
                assertSame("Library preserves title identity", data.movies.first(), saved.single())
                geometry = verifyGeometry(shell, viewport, theme)
            } catch (failure: AssertionError) { error = failure.message ?: "Native geometry assertion failed" }
              catch (failure: IllegalArgumentException) { error = failure.message ?: "Native geometry target missing" }
              catch (failure: NoSuchElementException) { error = failure.message ?: "Native callback missing" }
            if (error != null) failures += "${theme.displayName} ${viewport.family}: $error"
            return JSONObject().put("theme", theme.displayName).put("stored_theme_id", theme.id).put("form_factor", viewport.family)
                .put("qualifiers", viewport.qualifiers).put("viewport_px", pair(viewport.width, viewport.height))
                .put("viewport_dp", pair(viewport.widthDp, viewport.heightDp)).put("density", viewport.density.toDouble())
                .put("density_dpi", (viewport.density * 160).roundToInt())
                .put("system_insets_dp", JSONObject().put("top", viewport.topInsetDp).put("bottom", viewport.bottomInsetDp))
                .put("file", filename).put("caption_outside_viewport", true)
                .put("reference", art.referenceRecord(theme, if (viewport.tv) "tv" else "phone"))
                .put("reference_relation", when (viewport.family) {
                    "phone" -> "Matching reference pixel canvas at density 2; representative system insets"
                    "tv" -> "Matching reference pixel canvas; native TV uses a 960-wide logical canvas"
                    else -> "Adaptive 720dp touch window; no exact Fold reference was supplied"
                }).put("native_geometry", geometry).put("geometry_passed", error == null).put("geometry_error", error ?: JSONObject.NULL)
        } finally { controller.pause().stop().destroy(); shadowOf(Looper.getMainLooper()).idle() }
    }

    private fun verifyGeometry(shell: ConceptShellViews, viewport: Viewport, theme: ThemeSpec): JSONObject {
        val root = shell.root
        assertEquals(viewport.width, root.width); assertEquals(viewport.height, root.height)
        val wordmark = target(root, if (viewport.tv) "concept:wordmark" else "cameo:wordmark") as TextView
        val wordmarkLayout = requireNotNull(wordmark.layout) { "Cameo wordmark must be laid out" }
        for (line in 0 until wordmarkLayout.lineCount) assertEquals("CAMEO wordmark must fit without ellipsis", 0, wordmarkLayout.getEllipsisCount(line))
        val watching = target(root, if (viewport.tv) "concept:row:continue" else "mobile:continue-row")
        val discover = target(root, if (viewport.tv) "concept:row:discover" else "mobile:discover-row")
        val watchingPrefix = if (viewport.tv) "concept:resume:" else "mobile:resume:"
        val discoverPrefix = if (viewport.tv) "concept:media:" else "mobile:media:"
        val watchingCards = descendants(watching).filter { (it.tag as? String)?.startsWith(watchingPrefix) == true }
        val discoverCards = descendants(discover).filter { (it.tag as? String)?.startsWith(discoverPrefix) == true }
        val count = if (viewport.tv) 3 else 2
        assertTrue("Native continue cards exist", watchingCards.size >= count)
        watchingCards.take(count).forEach { assertVisibleFraction(root, it, .95, "Continue Watching") }
        val discoveryCount = if (viewport.tv) 5 else 2
        assertTrue("Native discovery cards exist", discoverCards.size >= discoveryCount)
        discoverCards.take(discoveryCount).forEach { assertVisibleFraction(root, it, if (viewport.tv) .95 else .35, "Discover") }
        if (viewport.family == "phone") {
            for (label in listOf("Home", "Browse", "Anime", "Library", "Search")) {
                val text = descendants(root).filterIsInstance<TextView>().firstOrNull {
                    it.text.toString() == label && bounds(root, it).top >= viewport.height * .8
                }
                assertTrue("$label remains visible in pinned phone navigation", text != null)
                assertInside(root, requireNotNull(text), "$label navigation")
                val button = generateSequence<View>(text) { it.parent as? View }.firstOrNull { it.isClickable }
                assertTrue("$label has a native touch action", button != null)
                assertTrue("$label has a 48dp touch height", requireNotNull(button).height >= (48 * viewport.density).roundToInt())
            }
        } else shell.navigationViews.values.distinct().forEach { assertInside(root, it, "${theme.displayName} navigation") }
        return JSONObject().put("wordmark", rect(bounds(root, wordmark)))
            .put("explore_control", rect(bounds(root, target(root, if (viewport.tv) "concept:hero:explore" else "mobile:hero:primary"))))
            .put("library_control", rect(bounds(root, target(root, if (viewport.tv) "concept:hero:library" else "mobile:hero:library"))))
            .put("continue_row", rect(bounds(root, watching))).put("discover_row", rect(bounds(root, discover)))
            .put("initial_complete_continue_cards", count).put("initial_visible_discover_cards", discoveryCount)
            .put("continue_cards", JSONArray(watchingCards.take(count).map { rect(bounds(root, it)) }))
            .put("discover_cards", JSONArray(discoverCards.take(discoveryCount).map { rect(bounds(root, it)) }))
    }

    private fun sampleData(tv: Boolean): ConceptHomeData {
        fun item(id: Int, title: String, art: String) = MediaItemData("tt${7_000_000 + id}", "movie", title, "2026",
            poster = "fixture:poster:$art", background = "fixture:background:$art", genres = listOf("Sci-Fi"), runtime = "2h 08m")
        val hero = item(0, "THE LAST HORIZON", "hero")
        val silent = item(1, "SILENT TIDE", "silent"); val after = item(2, "AFTERLIGHT", "afterlight")
        val wild = item(3, "WILD ATLAS", "wild"); val sky = item(4, "SKYBOUND", "sky"); val city = item(5, "CITY OF ECHOES", "city")
        val movies = if (tv) listOf(hero, silent, after, wild, sky, city) else listOf(hero, wild, city, sky, silent, after)
        val recent = (if (tv) listOf(silent, after, sky) else listOf(silent, after)).mapIndexed { index, media ->
            PlaybackStore.Recent("synthetic-concept-resume-$index", media.title, PlaybackIdentity(media.imdbId, "movie", media.title),
                if (index == 0) 3_720_000 else 1_680_000, 7_440_000)
        }
        return ConceptHomeData(movies, emptyList(), recent)
    }

    private fun saveImage(file: File, root: View, theme: ThemeSpec, viewport: Viewport, referenceArtwork: Boolean) {
        val large = viewport.density == 2f || viewport.tv
        val bitmap = Bitmap.createBitmap(viewport.width, viewport.height + if (large) 64 else 44, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap); canvas.drawColor(theme.palette.background); root.draw(canvas)
            val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            canvas.drawRect(0f, viewport.height.toFloat(), viewport.width.toFloat(), bitmap.height.toFloat(), label)
            label.color = Color.rgb(34, 40, 48); label.textSize = if (large) 18f else 12f
            label.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            val left = if (large) 16f else 10f; val line = if (large) 25f else 17f
            canvas.drawText("SIMULATED NATIVE UI · ${theme.displayName} · ${viewport.family.uppercase(Locale.ROOT)} · ${viewport.width}×${viewport.height} px @ ${viewport.density}×", left, viewport.height + line, label)
            canvas.drawText("${if (referenceArtwork) "Reference artwork samples" else "Illustrative fixture artwork"}; no physical-device capture.", left, viewport.height + line * 2, label)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            assertTrue(file.length() > 1_000)
        } finally { bitmap.recycle() }
    }

    private fun target(root: View, tag: String): View = requireNotNull(descendants(root).firstOrNull { it.tag == tag }) { "Missing native view $tag" }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun bounds(root: ViewGroup, view: View): Rect = Rect(0, 0, view.width, view.height).also { root.offsetDescendantRectToMyCoords(view, it) }
    private fun assertInside(root: ViewGroup, view: View, label: String) {
        val b = bounds(root, view)
        assertTrue("$label has nonzero bounds", view.width > 0 && view.height > 0)
        assertTrue("$label $b inside ${root.width}×${root.height}", b.left >= -1 && b.top >= -1 && b.right <= root.width + 1 && b.bottom <= root.height + 1)
    }
    private fun assertVisibleFraction(root: ViewGroup, view: View, minimum: Double, label: String) {
        val b = bounds(root, view); val visible = Rect()
        assertTrue("$label is visible", view.getGlobalVisibleRect(visible))
        val fraction = visible.width().toDouble() * visible.height() / (b.width().toDouble() * b.height()).coerceAtLeast(1.0)
        assertTrue("$label visible fraction $fraction >= $minimum; bounds=$b", fraction >= minimum)
    }
    private fun pair(width: Int, height: Int) = JSONObject().put("width", width).put("height", height)
    private fun rect(value: Rect) = JSONArray(listOf(value.left, value.top, value.right, value.bottom))
}
