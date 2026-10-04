package com.asmr.player.data.remote.otomekoe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OtomeKoeClientTest {
    @Test
    fun buildSearchUrl_usesWordPressPathFormAndCarriesNoQuery() {
        // 带查询串的入口会被 Cloudflare 判为机器人（403 + cf-mitigated: challenge）。
        val url = OtomeKoeClient.buildSearchUrl("内射", page = 1)

        assertEquals("https://otomekoe.moe/search/%E5%86%85%E5%B0%84/", url)
        assertFalse(url.contains('?'))
    }

    @Test
    fun buildSearchUrl_paginatesThroughPathSegments() {
        assertEquals(
            "https://otomekoe.moe/search/RJ01078320/page/3/",
            OtomeKoeClient.buildSearchUrl("RJ01078320", page = 3)
        )
    }

    @Test
    fun buildSearchUrl_neutralisesSlashAndSpaceInKeyword() {
        assertEquals(
            "https://otomekoe.moe/search/a%20b%20c/",
            OtomeKoeClient.buildSearchUrl("a/b c", page = 1)
        )
    }
}
