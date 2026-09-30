package com.codingcow.ikitty

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 同步开关的读取对**两种存储表示**都要成立。
 *
 * 两端把同一个键存成了不同的类型：Android 的同步命名空间把值按字面量存成字符串
 * （`"true"`），iOS 的 `NSUserDefaults` 支持布尔、读回来是 `SettingValue.Flag`。
 * 读取侧只认其中一种的后果是另一端的开关永远读成 false——界面显示"已打开"，
 * 下一次同步却仍然不带 API Key，而用户没有任何地方能看到这件事。
 */
class SyncFlagTest {

    /** 只装一个键的存储，用来固定"这个键在介质里是什么类型"。 */
    private class FakeStore(private val entries: Map<String, SettingValue>) : KeyValueStore {
        private val state = MutableStateFlow(entries)
        override val values: Flow<Map<String, SettingValue>> = state
        override suspend fun put(entries: Map<String, SettingValue>) {
            state.value = state.value + entries
        }
    }

    private fun storeWith(value: SettingValue) =
        KeyValueSyncCredentialStore(FakeStore(mapOf(SyncKeys.INCLUDE_API_KEY to value)))

    @Test
    fun `the api key switch reads a boolean flag`() = runTest {
        assertTrue(storeWith(SettingValue.Flag(true)).includeApiKey(), "iOS 存的是布尔")
    }

    @Test
    fun `the api key switch reads the string literal`() = runTest {
        // Android 的同步命名空间把所有值都按字面量存成字符串。
        assertTrue(storeWith(SettingValue.Str("true")).includeApiKey(), "Android 存的是 \"true\"")
    }
}
