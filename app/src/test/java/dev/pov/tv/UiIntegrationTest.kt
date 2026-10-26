package dev.pov.tv

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.pov.tv.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ExecutorService

/** Actual native screen behavior, with no account/network/native-player startup. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land-mdpi")
class UiIntegrationTest {
    private class Credentials : ProviderCredentials {
        private val values = mutableMapOf("realdebrid.token" to "test-token", "realdebrid.account_revision" to "old")
        override fun get(key: String) = values[key].orEmpty()
        override fun putAll(values: Map<String, String>) { this.values.putAll(values) }
    }
    private val movie = PlaybackIdentity("tt1234567", "movie", "Example")
    private val variants = listOf(
        ProviderFile("transfer:3", "Example.1080p.mkv", 200),
        ProviderFile("transfer:9", "Example.2160p.mkv", 400),
        ProviderFile("transfer:15", "Example.Directors.Cut.mkv", 500))

    @Test fun `provider version screen preserves every actual file as a separate remote target`() {
        val (activity, body, _) = screen()
        try {
            invoke(activity, "providerVersions", arrayOf(String::class.java, List::class.java, PlaybackIdentity::class.java, String::class.java, String::class.java),
                "realdebrid", variants, movie, "transfer:9", "old")
            val rows = descendants(body).filterIsInstance<TvChoiceRow>()
            assertEquals(3, rows.size)
            assertEquals(variants.map { "provider-file:realdebrid:${it.id}" }, rows.map { it.tag })
            assertTrue(rows.all { it.isFocusable && it.isClickable })
            assertEquals(rows[1].id, rows[0].nextFocusDownId)
            assertEquals(rows[2].id, rows[1].nextFocusDownId)
        } finally { shutdown(activity) }
    }

    @Test fun `restoring old account version screen never reuses its file choices`() {
        val (activity, body, credentials) = screen()
        try {
            credentials.putAll(mapOf("realdebrid.token" to "new-token", "realdebrid.account_revision" to "new"))
            invoke(activity, "providerVersions", arrayOf(String::class.java, List::class.java, PlaybackIdentity::class.java, String::class.java, String::class.java),
                "realdebrid", variants, movie, "transfer:9", "old")
            assertTrue(descendants(body).filterIsInstance<TvChoiceRow>().isEmpty())
            assertTrue(descendants(body).filterIsInstance<TextView>().any { it.text.toString().contains("account changed") })
        } finally { shutdown(activity) }
    }

    @Test fun `already rendered file cannot be played after replacing its provider account`() {
        val (activity, body, credentials) = screen()
        try {
            invoke(activity, "providerVersions", arrayOf(String::class.java, List::class.java, PlaybackIdentity::class.java, String::class.java, String::class.java),
                "realdebrid", variants, movie, "transfer:9", "old")
            val row = descendants(body).filterIsInstance<TvChoiceRow>()[1]
            credentials.putAll(mapOf("realdebrid.token" to "new-token", "realdebrid.account_revision" to "new"))
            row.performClick()
            assertFalse(field(activity, "launchingPlayer") as Boolean)
            assertNull(field(activity, "resolvingGeneration"))
        } finally { shutdown(activity) }
    }

    @Test fun `old cloud folder back stack entry is rejected before requesting new account data`() {
        val (activity, body, credentials) = screen()
        try {
            credentials.putAll(mapOf("realdebrid.token" to "new-token", "realdebrid.account_revision" to "new"))
            invoke(activity, "providerCloudFiles", arrayOf(String::class.java, ProviderFolder::class.java, String::class.java),
                "realdebrid", ProviderFolder("realdebrid", "old-folder", "Private old folder", true), "old")
            assertTrue(descendants(body).filterIsInstance<TvChoiceRow>().isEmpty())
            assertTrue(descendants(body).filterIsInstance<TextView>().any { it.text.toString().contains("account changed") })
        } finally { shutdown(activity) }
    }

    @Test fun `existing TorBox file version screen keeps every stable cloud identity`() {
        val (activity, body, _) = screen()
        try {
            val files = listOf(CloudFile(3, "Example.1080p.mkv", 200), CloudFile(9, "Example.2160p.mkv", 400))
            val prepared = PreparedSource.Files(CloudFolder(42, "torrents", "Example", true, files), files, movie, 9)
            invoke(activity, "preparedFiles", arrayOf(PreparedSource.Files::class.java), prepared)
            val rows = descendants(body).filterIsInstance<TvChoiceRow>()
            assertEquals(2, rows.size)
            assertEquals(listOf(TvChoiceKeys.file("torrents", 42, 3), TvChoiceKeys.file("torrents", 42, 9)), rows.map { it.tag })
            assertEquals(rows[1].id, rows[0].nextFocusDownId)
        } finally { shutdown(activity) }
    }

    private fun screen(): Triple<MainActivity, LinearLayout, Credentials> {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        activity.setTheme(R.style.AppTheme)
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(activity).apply { addView(body) }
        set(activity, "theme", ThemeSpec.CINEMATIC); set(activity, "publicPreview", true)
        set(activity, "body", body); set(activity, "scroller", scroll)
        set(activity, "heroBackdrop", FrameLayout(activity)); set(activity, "focusController", TvFocusController(body, scroll))
        val credentials = Credentials()
        val transport = object : ProviderTransport {
            override fun request(url: String, method: String, headers: Map<String, String>, fields: List<Pair<String, String>>?, json: JSONObject?): String =
                throw AssertionError("Offline UI regression reached provider network")
        }
        set(activity, "providers", ProviderHub(credentials, transport))
        return Triple(activity, body, credentials)
    }
    private fun invoke(activity: MainActivity, name: String, types: Array<Class<*>>, vararg values: Any?) =
        MainActivity::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(activity, *values)
    private fun set(activity: MainActivity, name: String, value: Any) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    private fun field(activity: MainActivity, name: String): Any? = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun shutdown(activity: MainActivity) { for (name in listOf("worker", "images", "authWorker")) (field(activity, name) as ExecutorService).shutdownNow() }
}
