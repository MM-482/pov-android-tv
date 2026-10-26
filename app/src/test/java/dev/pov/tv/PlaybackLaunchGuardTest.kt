package dev.pov.tv

import android.content.Context
import android.content.ContextWrapper
import android.content.DialogInterface
import android.content.SharedPreferences
import android.os.Looper
import android.widget.LinearLayout
import dev.pov.tv.data.PlaybackIdentity
import dev.pov.tv.data.SourceItem
import dev.pov.tv.data.TorBoxApi
import dev.pov.tv.data.TorBoxCredentials
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Exercises Main's actual launch callbacks without onCreate, account requests, or a player. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class PlaybackLaunchGuardTest {
    private val identity = PlaybackIdentity("tt1234567", "movie", "Offline fixture")
    private val key = "launch-guard-fixture"

    @Test fun `old TorBox source revision is rejected before busy state or worker submission`() {
        val fixture = fixture()
        try {
            var token = "old-account"
            var requests = 0
            val credentials = object : TorBoxCredentials {
                override fun get(key: String) = if (key == "torbox.token") token else ""
                override fun put(key: String, value: String) { token = value }
            }
            val api = TorBoxApi(credentials, { _, _, _, _ -> requests++; error("Unexpected account request") })
            set(fixture.activity, "torbox", api)
            val revision = api.accountRevision()
            token = "replacement-account"
            val worker = RejectingWorker()
            (field(fixture.activity, "worker") as ExecutorService).shutdownNow()
            set(fixture.activity, "worker", worker)

            invoke(fixture.activity, "resolve", arrayOf(SourceItem::class.java, PlaybackIdentity::class.java, String::class.java),
                SourceItem("Old cached result", "offline", infoHash = "a".repeat(40), cached = true), identity, revision)

            assertEquals(0, requests)
            assertEquals(0, worker.submissions)
            assertNull(field(fixture.activity, "resolvingGeneration"))
            assertFalse(field(fixture.activity, "launchingPlayer") as Boolean)
            assertEquals(0, fixture.body.childCount)
            assertTrue(ShadowToast.getTextOfLatestToast().toString().contains("account changed"))
        } finally { fixture.close() }
    }

    @Test fun `initial launch releases guard when durable history commit fails`() =
        failedLaunch(startOver = false, historyFails = true)

    @Test fun `Start over releases guard when durable history commit fails`() =
        failedLaunch(startOver = true, historyFails = true)

    @Test fun `initial launch releases guard when protected request validation fails`() =
        failedLaunch(startOver = false, historyFails = false)

    @Test fun `Start over releases guard when protected request validation fails`() =
        failedLaunch(startOver = true, historyFails = false)

    private fun failedLaunch(startOver: Boolean, historyFails: Boolean) {
        val fixture = fixture()
        try {
            val preferences = fixture.activity.getSharedPreferences("playback", Context.MODE_PRIVATE)
            preferences.edit().clear().commit()
            var commits = 0
            val historyContext = object : ContextWrapper(fixture.activity) {
                override fun getApplicationContext(): Context = this
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                    if (name != "playback") super.getSharedPreferences(name, mode)
                    else object : SharedPreferences by preferences {
                        override fun edit(): SharedPreferences.Editor {
                            val editor = preferences.edit()
                            return object : SharedPreferences.Editor by editor {
                                // Fluent writes must keep this wrapper or commit bypasses fault injection.
                                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                                    editor.putString(key, value); return this
                                }
                                override fun remove(key: String?): SharedPreferences.Editor {
                                    editor.remove(key); return this
                                }
                                override fun clear(): SharedPreferences.Editor {
                                    editor.clear(); return this
                                }
                                override fun commit(): Boolean { commits++; return if (historyFails) false else editor.commit() }
                            }
                        }
                    }
            }
            val history = PlaybackStore(historyContext)
            if (startOver) history.save(key, identity.title, identity, 120_000, 600_000)
            set(fixture.activity, "history", history)
            // Cookie is deliberately unsupported; validation fails before any credential handoff.
            val headers = if (historyFails) emptyMap() else mapOf("Cookie" to "fixture-secret-never-shown")
            invoke(fixture.activity, "play", arrayOf(String::class.java, String::class.java,
                PlaybackIdentity::class.java, String::class.java, Map::class.java),
                "https://members.easynews.com/offline-fixture.mkv", identity.title, identity, key, headers)

            if (startOver) {
                val dialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
                assertTrue(dialog.isShowing)
                assertTrue(field(fixture.activity, "launchingPlayer") as Boolean)
                assertEquals(0, commits)
                dialog.getButton(DialogInterface.BUTTON_NEUTRAL).performClick()
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("The actual beginPlayback durable write must be attempted", 1, commits)
            assertFalse(field(fixture.activity, "launchingPlayer") as Boolean)
            assertNull(shadowOf(fixture.activity).nextStartedActivity)
            val message = ShadowToast.getTextOfLatestToast()?.toString().orEmpty()
            assertTrue("The failure must be visible", message.isNotBlank())
            assertFalse(message.contains("fixture-secret"))
        } finally { fixture.close() }
    }

    private fun fixture(): Fixture {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()
        activity.setTheme(R.style.AppTheme)
        val body = LinearLayout(activity)
        activity.setContentView(body)
        set(activity, "theme", ThemeSpec.CINEMATIC)
        set(activity, "body", body)
        controller.visible().windowFocusChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        return Fixture(activity, body)
    }

    private class Fixture(val activity: MainActivity, val body: LinearLayout) {
        fun close() {
            ShadowAlertDialog.getLatestAlertDialog()?.dismiss()
            for (name in listOf("worker", "images", "authWorker")) {
                (field(activity, name) as ExecutorService).shutdownNow()
            }
            activity.finish()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private class RejectingWorker : AbstractExecutorService() {
        var submissions = 0
        private var stopped = false
        override fun execute(command: Runnable) { submissions++; error("Stale selection was queued") }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> { stopped = true; return mutableListOf() }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = stopped
    }

    companion object {
        private fun invoke(activity: MainActivity, name: String, types: Array<Class<*>>, vararg values: Any?) =
            MainActivity::class.java.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(activity, *values)
        private fun set(activity: MainActivity, name: String, value: Any) =
            MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
        private fun field(activity: MainActivity, name: String): Any? =
            MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    }
}
