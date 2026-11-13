package dev.pov.tv.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CatalogGenreParityTest {
    @Test fun `movie genre and page use official Cinemeta path extras accepted by public cache`() {
        var requested = ""
        val api = CatalogApi { url -> requested = url; JSONObject("""{"metas":[]}""") }
        api.popular("movie", 100, "Sci-Fi")
        assertEquals("https://v3-cinemeta.strem.io/catalog/movie/top/skip=100&genre=Sci-Fi.json", requested)
        PublicCatalogCache.validateAddress(requested)
        api.popular("movie", genre = "All genres")
        assertEquals("https://v3-cinemeta.strem.io/catalog/movie/top/skip=0.json", requested)
    }

    @Test fun `series genres include official series-only formats and reject them for movies`() {
        var requests = 0
        val api = CatalogApi { requests++; JSONObject("""{"metas":[]}""") }
        assertEquals(19, api.genres("movie").size)
        assertEquals(22, api.genres("series").size)
        api.popular("series", genre = "Reality-TV")
        try { api.popular("movie", genre = "Reality-TV"); fail("Expected movie genre rejection") } catch (_: IllegalArgumentException) { }
        try { api.popular("movie", genre = "Action&apikey=private-fixture"); fail("Expected unsupported genre rejection") } catch (_: IllegalArgumentException) { }
        assertEquals(1, requests)
    }
}
