package com.example.aicat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateModelsTest {

    @Test
    fun `解析 release 取出版本与 APK 地址`() {
        val info = parseLatestRelease(
            """
            {
              "tag_name": "v0.2.0",
              "body": "新增软件更新",
              "published_at": "2026-09-15T04:00:00Z",
              "assets": [
                {
                  "name": "iKitty-v0.2.0.apk",
                  "browser_download_url": "https://example.com/iKitty-v0.2.0.apk",
                  "size": 12345
                }
              ]
            }
            """.trimIndent()
        )

        assertNotNull(info)
        assertEquals("0.2.0", info!!.version)
        assertEquals("v0.2.0", info.tagName)
        assertEquals("新增软件更新", info.notes)
        assertEquals("https://example.com/iKitty-v0.2.0.apk", info.apkUrl)
        assertEquals(12345L, info.apkSizeBytes)
        assertEquals(1789444800000L, info.publishedAt)
    }

    @Test
    fun `多个 APK 时优先选与版本同名的那个`() {
        val info = parseLatestRelease(
            """
            {
              "tag_name": "v0.3.0",
              "assets": [
                {"name": "iKitty-debug.apk", "browser_download_url": "https://example.com/debug.apk"},
                {"name": "iKitty-v0.3.0.apk", "browser_download_url": "https://example.com/release.apk"}
              ]
            }
            """.trimIndent()
        )

        assertEquals("https://example.com/release.apk", info?.apkUrl)
    }

    @Test
    fun `没有 APK 附件时返回 null`() {
        assertNull(
            parseLatestRelease(
                """{"tag_name":"v0.2.0","assets":[{"name":"source.zip","browser_download_url":"https://example.com/s.zip"}]}"""
            )
        )
    }

    @Test
    fun `预发布版本不作为更新`() {
        assertNull(parseLatestRelease("""{"tag_name":"v0.2.0","prerelease":true,"assets":[]}"""))
    }

    @Test
    fun `返回体不是 JSON 时返回 null`() {
        assertNull(parseLatestRelease("<html>502 Bad Gateway</html>"))
    }

    @Test
    fun `缺少 tag 时返回 null`() {
        assertNull(parseLatestRelease("""{"assets":[{"name":"a.apk","browser_download_url":"https://e.com/a.apk"}]}"""))
    }

    @Test
    fun `版本比较识别新旧与相等`() {
        assertEquals(1, compareVersions("0.2.0", "0.1.0"))
        assertEquals(-1, compareVersions("0.1.0", "0.2.0"))
        assertEquals(0, compareVersions("v0.1.0", "0.1.0"))
        assertEquals(1, compareVersions("1.0", "0.9.9"))
        assertEquals(-1, compareVersions("0.1", "0.1.1"))
    }

    @Test
    fun `同一基线的预发布版本更旧`() {
        assertEquals(-1, compareVersions("0.2.0-beta.1", "0.2.0"))
        assertEquals(1, compareVersions("0.2.0", "0.2.0-beta.1"))
    }

    @Test
    fun `版本号会去掉 v 前缀`() {
        assertEquals("0.2.0", normalizeVersion(" v0.2.0 "))
        assertEquals("0.2.0", normalizeVersion("V0.2.0"))
    }
}
