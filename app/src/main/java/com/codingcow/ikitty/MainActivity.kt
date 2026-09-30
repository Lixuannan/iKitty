package com.codingcow.ikitty

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/** 温暖柔和的奶油橘配色。 */
private val CatLightColors = lightColorScheme(
    primary = Color(0xFFE08A5F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFE2CE),
    onPrimaryContainer = Color(0xFF4A2A16),
    secondary = Color(0xFFB58A6A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFBEBDD),
    onSecondaryContainer = Color(0xFF4A3524),
    tertiary = Color(0xFFE98D9A),
    background = Color(0xFFFFF8F2),
    onBackground = Color(0xFF463A33),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF463A33),
    surfaceVariant = Color(0xFFF5EAE0),
    onSurfaceVariant = Color(0xFF7C6A5E),
    outlineVariant = Color(0xFFEADCD0),
    error = Color(0xFFD4665A),
    onError = Color(0xFFFFFFFF)
)

class MainActivity : ComponentActivity() {
    /**
     * 与 Compose 里 `viewModel()` 拿到的是**同一个实例**：两者都以这个 Activity 作为
     * `ViewModelStoreOwner`。所以在前台事件里调它，改的就是界面上那一个。
     */
    private val vm: CatChatViewModel by viewModels()

    /**
     * 进程级前台观察者，用来触发云同步（见 [CatChatViewModel.onAppForeground]）。
     *
     * 观察 `ProcessLifecycleOwner` 而不是 Activity 自身的生命周期：旋转屏幕会让 Activity
     * 重新走一遍 `ON_START`，而用户并没有离开过应用，没必要为此同步一次。
     * 进程 `ON_START` 同时覆盖冷启动，所以 ViewModel 的 `init` 里不再单独同步。
     */
    private val foregroundSync = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            vm.onAppForeground()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ProcessLifecycleOwner.get().lifecycle.addObserver(foregroundSync)
        setContent {
            MaterialTheme(colorScheme = CatLightColors) {
                CatChatScreen(vm)
            }
        }
    }

    override fun onDestroy() {
        // 进程生命周期比 Activity 活得久，不摘掉的话这个观察者会一直握着已经结束的 VM。
        ProcessLifecycleOwner.get().lifecycle.removeObserver(foregroundSync)
        super.onDestroy()
    }
}
