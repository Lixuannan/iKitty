package com.codingcow.ikitty

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

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
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = CatLightColors) {
                CatChatScreen()
            }
        }
    }
}
