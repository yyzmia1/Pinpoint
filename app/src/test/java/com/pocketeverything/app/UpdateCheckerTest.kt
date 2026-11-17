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
        assertTrue(UpdateChecker.isNewerVersion("v1.9.0", "v1.10.0"))
        assertFalse(UpdateChecker.isNewerVersion("v1.10.0", "v1.9.0"))
        assertTrue(UpdateChecker.isNewerVersion("1.2.9", "1.2.10"))
    }

    @Test
    fun `respects 24h cooldown interval for silent startup checks`() {
        val base = 1_700_000_000_000L
        assertTrue(UpdateChecker.shouldRunSilentCheck(0L, base))
        assertFalse(UpdateChecker.shouldRunSilentCheck(base, base + UpdateChecker.SILENT_CHECK_INTERVAL_MS - 1L))
        assertTrue(UpdateChecker.shouldRunSilentCheck(base, base + UpdateChecker.SILENT_CHECK_INTERVAL_MS))
    }

    @Test
    fun `filters ignored version tag during silent checks`() {
        assertTrue(UpdateChecker.shouldPromptSilentUpdate("v1.3.0", "1.2.6", ""))
        assertFalse(UpdateChecker.shouldPromptSilentUpdate("v1.3.0", "1.2.6", "v1.3.0"))
        assertTrue(UpdateChecker.shouldPromptSilentUpdate("v1.3.1", "1.2.6", "v1.3.0"))
        assertFalse(UpdateChecker.shouldPromptSilentUpdate("v1.2.6", "1.2.6", ""))
    }

    @Test
    fun `parses release history array with dates and prerelease flag`() {
        val listJson = """
            [
              {
                "tag_name": "v1.3.0-rc1",
                "prerelease": true,
                "published_at": "2026-11-17T08:00:00Z",
                "html_url": "https://github.com/yyzmia1/Pinpoint/releases/tag/v1.3.0-rc1",
                "body": "### Beta\n- Monet dynamic theme",
                "assets": []
              },
              {
                "tag_name": "v1.2.6",
                "prerelease": false,
                "published_at": "2026-11-16T12:00:00Z",
                "html_url": "https://github.com/yyzmia1/Pinpoint/releases/tag/v1.2.6",
                "body": "- Initial Pinpoint release",
                "assets": [
                  {
                    "name": "Pinpoint-v1.2.6-release.apk",
                    "size": 10485760,
                    "browser_download_url": "https://example.com/Pinpoint-v1.2.6-release.apk"
                  }
                ]
              }
            ]
        """.trimIndent()

        val list = UpdateChecker.parseReleaseListJson(listJson)
        assertEquals(2, list.size)
        assertEquals("v1.3.0-rc1", list[0].tagName)
        assertTrue(list[0].isPrerelease)
        assertEquals("2026-11-17", list[0].publishedDate)
        assertEquals("v1.2.6", list[1].tagName)
        assertFalse(list[1].isPrerelease)
        assertEquals("2026-11-16", list[1].publishedDate)
        assertEquals(10485760L, list[1].apkSizeBytes)
    }

    @Test
    fun `respects stable and beta channel switches and compares prerelease versions`() {
        val releases = UpdateChecker.parseReleaseListJson(
            """
            [
              {
                "tag_name": "v1.4.0-beta2",
                "prerelease": true,
                "published_at": "2026-11-18T00:00:00Z",
                "html_url": "https://example.com/beta2",
                "body": "Beta 2",
                "assets": []
              },
              {
                "tag_name": "v1.3.5",
                "prerelease": false,
                "published_at": "2026-11-17T12:00:00Z",
                "html_url": "https://example.com/stable",
                "body": "Stable",
                "assets": []
              }
            ]
            """.trimIndent()
        )

        // Default: stable ON, beta OFF -> picks v1.3.5
        assertEquals("v1.3.5", UpdateChecker.selectEligibleUpdate(releases, "1.3.3", receiveStable = true, receiveBeta = false)?.tagName)
        // Both closed -> pushes nothing
        assertEquals(null, UpdateChecker.selectEligibleUpdate(releases, "1.3.3", receiveStable = false, receiveBeta = false))
        // Stable OFF, beta ON -> ignores v1.3.5, picks v1.4.0-beta2
        assertEquals("v1.4.0-beta2", UpdateChecker.selectEligibleUpdate(releases, "1.3.3", receiveStable = false, receiveBeta = true)?.tagName)
        // Both ON -> picks newer v1.4.0-beta2
        assertEquals("v1.4.0-beta2", UpdateChecker.selectEligibleUpdate(releases, "1.3.3", receiveStable = true, receiveBeta = true)?.tagName)

        // Pre-release SemVer ordering
        assertTrue(UpdateChecker.isNewerVersion("1.4.0-beta1", "v1.4.0-beta2"))
        assertTrue(UpdateChecker.isNewerVersion("1.4.0-beta2", "v1.4.0-rc1"))
        assertTrue(UpdateChecker.isNewerVersion("1.4.0-rc1", "v1.4.0"))
        assertFalse(UpdateChecker.isNewerVersion("1.4.0", "v1.4.0-rc1"))
    }

    @Test
    fun `ranks dev canary nightly snapshot below alpha below beta below rc and checks exact version identity`() {
        assertTrue(UpdateChecker.isNewerVersion("1.4.0-nightly.1", "v1.4.0-alpha1"))
        assertTrue(UpdateChecker.isNewerVersion("1.4.0-canary", "v1.4.0-alpha1"))
        assertTrue(UpdateChecker.isNewerVersion("1.4.0-dev.1", "v1.4.0-alpha1"))
        assertTrue(UpdateChecker.isNewerVersion("1.4.0-snapshot", "v1.4.0-beta1"))
        assertTrue(UpdateChecker.isNewerVersion("1.4.0-alpha2", "v1.4.0-beta1"))
        assertTrue(UpdateChecker.isNewerVersion("1.4.0-beta2", "v1.4.0-rc1"))
        assertFalse(UpdateChecker.isNewerVersion("1.4.0-beta1", "v1.4.0-nightly"))
        assertFalse(UpdateChecker.isNewerVersion("1.4.0-alpha1", "v1.4.0-dev"))
        assertTrue(UpdateChecker.isNewerVersion("1.4.0-nightly.20261117", "v1.4.0-nightly.20261118"))

        // Exact identity must NOT treat 1.4.0-beta1 as 1.4.0
        assertFalse(UpdateChecker.isSameVersion("1.4.0-beta1", "v1.4.0"))
        assertTrue(UpdateChecker.isSameVersion("1.4.0-beta1", "v1.4.0-beta1"))
        assertTrue(UpdateChecker.isSameVersion("1.4.0-beta1-debug", "v1.4.0-beta1"))
        assertTrue(UpdateChecker.isSameVersion("1.4.0-debug", "v1.4.0"))
        assertTrue(UpdateChecker.isSameVersion("1.4.0-3-g1234567", "v1.4.0"))
        assertTrue(UpdateChecker.isSameVersion("1.4.0-alpha.1+build9", "v1.4.0-alpha1"))
    }
}
