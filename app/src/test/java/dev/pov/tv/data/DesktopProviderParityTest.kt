package dev.pov.tv.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URI

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DesktopProviderParityTest {
    private val source = SourceItem("Example", "Test", infoHash = "a".repeat(40))
    private val movie = PlaybackIdentity("tt1234567", "movie", "Example")
    private fun waiting(): ProviderPreparationWait {
        var now = 0L
        return ProviderPreparationWait({ now }, { now += it }, timeoutMs = 100, intervalMs = 1)
    }
    private fun transport(answer: (String, String, List<Pair<String, String>>?, JSONObject?) -> String) = object : ProviderTransport {
        override fun request(url: String, method: String, headers: Map<String, String>, fields: List<Pair<String, String>>?, json: JSONObject?) =
            answer(url, method, fields, json)
    }
    @Test fun `Debrid-Link creates once and waits for the original transfer before returning file identity`() {
        var created = 0; var reads = 0
        val http = transport { url, _, _, json ->
            if (URI(url).path.endsWith("/seedbox/add")) {
                created++; assertEquals(false, json!!.getBoolean("wait"))
                """{"success":true,"value":{"id":"original-transfer"}}"""
            } else {
                reads++
                """{"success":true,"value":[{"id":"original-transfer","downloadPercent":${if (reads == 1) 30 else 100},"files":[{"id":"7","name":"Example.mkv","downloadPercent":100,"size":100,"downloadUrl":"https://cdn.example/video"}]}]}"""
            }
        }
        val prepared = DebridLinkProvider(http, waiting()).prepare("token", source, movie)
        assertEquals(1, created); assertTrue(reads >= 2)
        assertEquals("original-transfer", prepared.folderId)
        assertEquals("original-transfer:7", prepared.files.single().id)
    }
    @Test fun `put io transfer IDs cannot become file IDs and nested prepared paths remain exact`() {
        var creations = 0; var transferReads = 0; val urls = mutableListOf<String>()
        val http = transport { url, _, _, _ ->
            urls += url
            when {
                url.endsWith("/transfers/add") -> { creations++; """{"status":"OK","transfer":{"id":50}}""" }
                url.endsWith("/transfers/50") -> { transferReads++; """{"status":"OK","transfer":{"id":50,"file_id":${if (transferReads == 1) 0 else 200}}}""" }
                url.endsWith("/files/200") -> """{"status":"OK","file":{"id":200,"name":"Collection","file_type":"FOLDER"}}"""
                url.endsWith("parent_id=200") -> """{"status":"OK","files":[{"id":201,"name":"Versions","file_type":"FOLDER"}]}"""
                url.endsWith("parent_id=201") -> """{"status":"OK","files":[{"id":303,"name":"Example.mkv","file_type":"VIDEO","size":123}]}"""
                url.contains("/files/303/url") -> """{"status":"OK","url":"https://cdn.example/exact-file"}"""
                else -> error("Unexpected request: ${URI(url).path}")
            }
        }
        val provider = PutIoProvider(http, waiting())
        val prepared = provider.prepare("token", source, movie)
        assertEquals(1, creations); assertEquals("200", prepared.folderId)
        assertEquals("303", prepared.files.single().id)
        assertEquals("Versions/Example.mkv", prepared.files.single().name)
        assertEquals("https://cdn.example/exact-file", provider.playback("token", prepared.files.single()).url)
        assertFalse(urls.any { it.contains("/files/50") })
    }
    @Test fun `Offcloud waits on one created request and rejects duplicate filename ambiguity`() {
        var creations = 0; var reads = 0
        val http = transport { url, _, _, _ ->
            if (URI(url).path == "/api/cloud") { creations++; """{"requestId":"one-request"}""" }
            else { reads++; if (reads == 1) "[]" else """[{"filename":"Example.mkv","url":"https://cdn.example/a"},{"filename":"Example.mkv","url":"https://cdn.example/b"}]""" }
        }
        val provider = OffcloudProvider(http, waiting())
        val prepared = provider.prepare("token", source, movie)
        assertEquals(1, creations); assertEquals(2, prepared.files.size)
        try { provider.playback("token", prepared.files.first()); fail("Ambiguous filename accepted") }
        catch (_: IllegalStateException) { }
    }
    @Test fun `AllDebrid polls the same pending link without unlocking or creating another transfer`() {
        var unlocks = 0; var polls = 0
        val http = transport { url, _, fields, _ ->
            if (url.endsWith("/link/unlock")) { unlocks++; """{"status":"success","data":{"delayed":99}}""" }
            else {
                assertTrue(url.endsWith("/link/delayed")); assertEquals(listOf("id" to "99"), fields)
                polls++; if (polls == 1) """{"status":"success","data":{"status":1}}"""
                else """{"status":"success","data":{"status":2,"link":"https://cdn.example/video"}}"""
            }
        }
        val result = AllDebridProvider(http, waiting()).playback("token", ProviderFile("id", "Example.mkv", link = "https://alldebrid.example/file"))
        assertEquals("https://cdn.example/video", result.url); assertEquals(1, unlocks); assertEquals(2, polls)
    }
    @Test fun `Real-Debrid waiting file remains selectable after safe descriptor reconstruction`() {
        var selected = false; var selections = 0
        val http = transport { url, _, fields, _ -> when {
            url.contains("/torrents/info/") -> """{"id":"transfer","status":"${if (selected) "downloaded" else "waiting_files_selection"}","files":[{"id":9,"path":"Example.mkv","selected":${if (selected) 1 else 0}}],"links":${if (selected) "[\"https://rd.example/file\"]" else "[]"}}"""
            url.contains("/torrents/selectFiles/") -> { selections++; assertEquals(listOf("files" to "9"), fields); selected = true; "" }
            else -> """{"download":"https://cdn.example/exact"}"""
        } }
        val provider = RealDebridProvider(http, waiting())
        val file = provider.files("token", "transfer").single()
        assertEquals("transfer:9", file.id)
        assertEquals("https://cdn.example/exact", provider.playback("token", file.copy(link = "")).url)
        assertEquals(1, selections)
        provider.playback("token", file)
        assertEquals(1, selections)
    }
    @Test fun `Real-Debrid cannot reselect an unselected file in an existing downloaded transfer`() {
        var calls = 0
        val provider = RealDebridProvider(transport { _, _, _, _ -> calls++
            """{"id":"transfer","status":"downloaded","files":[{"id":9,"path":"Example.mkv","selected":0}]}"""
        }, waiting())
        try { provider.playback("token", ProviderFile("transfer:9", "Example.mkv")); fail("Existing selection overwritten") }
        catch (_: IllegalStateException) { }
        assertEquals(1, calls)
    }
    @Test fun `preparation timeout and terminal account errors never execute another creation`() {
        var checks = 0
        try { waiting().await<String>("Example", "original-id") { checks++; null }; fail("Expected timeout") }
        catch (error: ApiException) { assertTrue(error.message.orEmpty().contains("original-id")) }
        assertTrue(checks > 1)
        var rejected = 0
        try { waiting().await<String>("Example", "original-id") { rejected++; throw ApiException(403, "Denied") }; fail("Expected rejection") }
        catch (error: ApiException) { assertEquals(403, error.code) }
        assertEquals(1, rejected)
    }
    @Test fun `Premiumize saved-account preparation creates one durable cloud transfer without changing instant playback`() {
        var creations = 0; var reads = 0
        val http = transport { url, _, _, _ -> when {
            url.endsWith("/transfer/create") -> { creations++; """{"status":"success","id":"transfer7"}""" }
            url.endsWith("/transfer/list") -> {
                reads++
                if (reads == 1) """{"status":"success","transfers":[{"id":"transfer7","status":"running"}]}"""
                else """{"status":"success","transfers":[{"id":"transfer7","status":"finished","folder_id":"folder8"}]}"""
            }
            url.endsWith("/folder/list?id=folder8") -> """{"status":"success","content":[{"id":"folder9","name":"Versions","type":"folder"}]}"""
            url.endsWith("/folder/list?id=folder9") -> """{"status":"success","content":[{"id":"file10","name":"Example.mkv","type":"file","size":123}]}"""
            url.endsWith("/item/details?id=file10") -> """{"status":"success","id":"file10","name":"Example.mkv","link":"https://cdn.example/exact"}"""
            else -> error("Unexpected request: ${URI(url).path}")
        } }
        val provider = PremiumizeProvider(http, waiting())
        val prepared = provider.prepareTransfer("token", source, movie)
        assertEquals(1, creations); assertEquals("transfer:transfer7", prepared.folderId)
        assertEquals("file10", prepared.files.single().id)
        assertEquals("Versions/Example.mkv", prepared.files.single().name)
        assertFalse(prepared.files.single().direct)
        assertEquals(prepared.files.single().id, provider.files("token", prepared.folderId).single().id)
        assertEquals("https://cdn.example/exact", provider.playback("token", prepared.files.single()).url)
    }
}
