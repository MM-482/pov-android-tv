package dev.pov.tv

import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CameoDiagnosticsTest {
    @Test fun `offline report excludes stored account and media details and never requests public checks`() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("pov_playback_preferences", 0)
        preferences.edit().putString("unrelated_secret", "account-private-fixture")
            .putString("caption_color", "https://private.example/token-fixture").commit()
        try {
            val report = CameoDiagnostics.report(context)
            assertTrue(report.contains("This is an offline report."))
            assertTrue(report.contains("color=WHITE"))
            assertFalse(report.contains("account-private-fixture"))
            assertFalse(report.contains("private.example"))
            assertFalse(report.contains("https://"))
            assertFalse(report.contains(context.filesDir.absolutePath))
        } finally { preferences.edit().remove("unrelated_secret").remove("caption_color").commit() }
    }

    @Test fun `connectivity summaries exclude response bodies redirects and exception secrets`() {
        fun probe(code: Int, text: String): String {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code)
                    .message("private-response-fixture").header("Location", "https://private.example/token-fixture")
                    .body(text.toResponseBody()).build()
            }.build()
            return CameoDiagnostics.probe(client.newCall(Request.Builder().url("https://public.example").build()), true)
        }
        assertEquals("HTTP 200; valid public JSON received.", probe(200, "{\"token\":\"private-fixture\"}"))
        assertEquals("HTTP 200; unexpected public document.", probe(200, "<html>private-fixture</html>"))
        assertEquals("HTTP 302; redirect not followed.", probe(302, "private-fixture"))
        val failing = OkHttpClient.Builder().addInterceptor { throw IOException("/private/path account-private-fixture") }.build()
        assertEquals("Network request failed.", CameoDiagnostics.probe(failing.newCall(Request.Builder()
            .url("https://public.example").build()), true))
    }
}
