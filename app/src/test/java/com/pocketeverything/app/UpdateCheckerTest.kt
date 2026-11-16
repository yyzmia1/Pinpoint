package com.pocketeverything.app

import com.pocketeverything.app.data.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun `parses GitHub release JSON and prefers release apk asset`() {
        val sampleJson = """
            {
              "tag_name": "v1.1.0",
              "html_url": "https://github.com/yyzmia1/Pinpoint/releases/tag/v1.1.0",
              "body": "新增严格正则与 Shizuku 向导\n修复通配符匹配",
              "assets": [
                {
                  "name": "Pinpoint-debug.apk",
                  "size": 31000000,
                  "browser_download_url": "https://example.com/debug.apk"
                },
                {
                  "name": "Pinpoint-v1.1.0-release.apk",
                  "size": 12900000,
                  "browser_download_url": "https://example.com/release.apk"
                }
              ]
            }
        """.trimIndent()

        val info = UpdateChecker.parseReleaseJson(sampleJson)
        assertNotNull(info)
        assertEquals("v1.1.0", info!!.tagName)
        assertEquals("https://example.com/release.apk", info.apkDownloadUrl)
        assertEquals(12900000L, info.apkSizeBytes)
        assertTrue(info.releaseNotes.contains("新增严格正则"))
    }

    @Test
    fun `compares semantic versions accurately across tags and git describe suffixes`() {
        assertTrue(UpdateChecker.isNewerVersion("1.0.0", "v1.1.0"))
        assertTrue(UpdateChecker.isNewerVersion("1.0.0-1-gaab3afc", "v1.0.1"))
        assertFalse(UpdateChecker.isNewerVersion("1.1.0", "v1.1.0"))
        assertFalse(UpdateChecker.isNewerVersion("1.1.0-3-g1234567", "v1.1.0"))
        assertFalse(UpdateChecker.isNewerVersion("1.2.0", "v1.1.9"))
    }
}
