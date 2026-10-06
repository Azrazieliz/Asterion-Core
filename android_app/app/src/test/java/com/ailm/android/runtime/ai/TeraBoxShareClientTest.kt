package com.ailm.android.runtime.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TeraBoxShareClientTest {
    private val client = TeraBoxShareClient()

    @Test
    fun `share keys are extracted from slash-s links with and without leading one`() {
        val keys = client.shareKeyCandidates("https://www.terabox.com/s/1AbCdEf123")
        assertEquals(listOf("1AbCdEf123", "AbCdEf123"), keys)
    }

    @Test
    fun `share keys are extracted from redirected surl query`() {
        val keys = client.shareKeyCandidates(
            "https://www.terabox.com/sharing/link?surl=AbCdEf123&from=share",
        )
        assertEquals(listOf("AbCdEf123"), keys)
    }

    @Test
    fun `javascript token is extracted from common web share forms`() {
        assertEquals(
            "token-123",
            client.extractJsToken("""<script>window.jsToken = "token-123";</script>"""),
        )
        assertEquals(
            "encoded-token",
            client.extractJsToken("foo fn%28%22encoded-token%22%29 bar"),
        )
    }

    @Test
    fun `unrelated html produces no token`() {
        assertTrue(client.extractJsToken("<html><body>share</body></html>").isBlank())
    }
}
