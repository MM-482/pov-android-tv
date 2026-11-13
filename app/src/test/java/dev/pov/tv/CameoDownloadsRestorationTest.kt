package dev.pov.tv

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class CameoDownloadsRestorationTest {
    private fun job(id:String="fixture-job",status:String="completed",kind:String="video")=JSONObject()
        .put("id",id).put("name","Example.mkv").put("destination","content://documents/video/$id")
        .put("status",status).put("bytes",1024).put("total",4096).put("kind",kind)

    @Test fun `restart marks active transfers interrupted and keeps nonvideo documents distinct`() {
        val saved=JSONArray().put(job(status="downloading")).put(job("fixture-nzb",kind="document"))
        val restored=CameoDownloads.restoreSaved(saved.toString())
        assertEquals(listOf("interrupted","completed"),restored.map{it.status})
        assertEquals(listOf("video","document"),restored.map{it.kind})
        assertTrue(restored.first().message.contains("restart"))
        assertEquals(1024L,restored.first().bytes)
    }

    @Test fun `corrupt rows duplicate ids and invalid destinations cannot silently erase jobs`() {
        listOf(JSONArray().put(job()).put("broken"),JSONArray().put(job()).put(job()),
            JSONArray().put(job().put("destination","https://example.test/file")),
            JSONArray().put(job(status="unknown")),JSONArray().put(job().put("bytes",-1))).forEach{array->
            assertTrue(runCatching{CameoDownloads.restoreSaved(array.toString())}.isFailure)
        }
    }
}
