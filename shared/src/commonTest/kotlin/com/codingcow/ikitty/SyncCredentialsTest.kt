package com.codingcow.ikitty

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 同步凭据的存取语义与账号密钥的长度策略。 */
class SyncCredentialsTest {

    private class FakeStore : KeyValueStore {
        private val state = MutableStateFlow<Map<String, SettingValue>>(emptyMap())
        override val values: Flow<Map<String, SettingValue>> = state
        override suspend fun put(entries: Map<String, SettingValue>) {
            state.value = state.value + entries
        }
    }

    private fun store() = KeyValueSyncCredentialStore(FakeStore()) { "test-device" }

    @Test
    fun `the stored key is trimmed and readable`() = runTest {
        val credentials = store()
        credentials.setAccountKey("  my-key-123  ")
        assertEquals("my-key-123", credentials.accountKey())
    }

    @Test
    fun `clearing the key keeps the cursor`() = runTest {
        val credentials = store()
        credentials.setAccountKey("my-key-123")
        credentials.setSinceRev(42)

        // 401 时只清密钥：用户很可能只是填错了 key，重填之后不该被当成全新的第一次同步。
        credentials.clearAccountKey()

        assertEquals("", credentials.accountKey())
        assertEquals(42L, credentials.sinceRev())
    }

    @Test
    fun `switching the key forgets everything tied to the old cloud space`() = runTest {
        val credentials = store()
        credentials.setAccountKey("old-key-123")
        credentials.setSinceRev(42)
        credentials.addPushedMessageIds(listOf("m1", "m2"))
        credentials.addDeletedMessageIds(listOf("m3"))

        credentials.clearSyncState()

        assertEquals(0L, credentials.sinceRev())
        assertTrue(credentials.pushedMessageIds().isEmpty())
        assertTrue(credentials.deletedMessageIds().isEmpty())
        // 密钥与设备身份不属于"云空间状态"，不该被清掉。
        assertEquals("old-key-123", credentials.accountKey())
        assertEquals("test-device", credentials.deviceId())
    }

    @Test
    fun `the device id is generated once and then stable`() = runTest {
        var generated = 0
        val credentials = KeyValueSyncCredentialStore(FakeStore()) {
            generated++
            "device-$generated"
        }

        val first = credentials.deviceId()
        assertEquals(first, credentials.deviceId(), "重复读取必须拿到同一个 id")
        assertEquals(1, generated, "只该生成一次")
    }

    @Test
    fun `pushed ids survive a round trip and are capped`() = runTest {
        val credentials = store()
        credentials.addPushedMessageIds(listOf("a", "b"))
        credentials.addPushedMessageIds(listOf("b", "c"))
        assertEquals(setOf("a", "b", "c"), credentials.pushedMessageIds())
        assertTrue(credentials.pushedMessageIds().none { it.isBlank() })
    }

    @Test
    fun `a saved service url is readable back without a sync`() = runTest {
        // 这条盯的是 iOS 上那个真实缺陷：凭据曾经只在同步那一个入口里被写，点「保存」不写，
        // 于是关掉设置页之后地址与密钥全丢。写入必须能脱离"同步"单独发生。
        val credentials = store()
        credentials.setServiceUrl("  https://sync.example.workers.dev/  ")
        credentials.setAccountKey("my-key-123")

        assertEquals("https://sync.example.workers.dev", credentials.serviceUrl())
        assertEquals("my-key-123", credentials.accountKey())
    }

    @Test
    fun `an unset service url reads back as empty rather than throwing`() = runTest {
        val credentials = store()
        assertEquals("", credentials.serviceUrl())
        assertEquals("", credentials.accountKey())
        assertTrue(credentials.pushedMessageIds().isEmpty())
    }

    @Test
    fun `include api key defaults to off and round trips`() = runTest {
        val credentials = store()
        assertTrue(!credentials.includeApiKey(), "默认必须是不上传 API Key")
        credentials.setIncludeApiKey(true)
        assertTrue(credentials.includeApiKey())
        credentials.setIncludeApiKey(false)
        assertTrue(!credentials.includeApiKey())
    }

    @Test
    fun `key strength follows the documented thresholds`() {
        assertEquals(SyncKeyStrength.EMPTY, SyncKeyStrength.of(""))
        assertEquals(SyncKeyStrength.EMPTY, SyncKeyStrength.of("   "))
        assertEquals(SyncKeyStrength.TOO_SHORT, SyncKeyStrength.of("abcd"))
        // 下限刻意设得很低：好记的口令是合法选择，代价由界面说清。
        assertEquals(SyncKeyStrength.WEAK, SyncKeyStrength.of("abcde"))
        assertEquals(SyncKeyStrength.WEAK, SyncKeyStrength.of("a".repeat(15)))
        assertEquals(SyncKeyStrength.STRONG, SyncKeyStrength.of("a".repeat(16)))
    }
}
