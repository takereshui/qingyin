package im.molan.music.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

private val Wine = Color(0xFF8C1636)
private val WineDark = Color(0xFF5A0620)
private val Rose = Color(0xFFFFD9E0)

fun lightWineScheme() = lightColorScheme(
    primary = Wine,
    onPrimary = Color.White,
    primaryContainer = Rose,
    onPrimaryContainer = Color(0xFF3D0012),
    secondary = Color(0xFF74565E),
    background = Color(0xFFFFF8F8),
    surface = Color(0xFFFFF8F8),
    surfaceVariant = Color(0xFFF5E5E8),
)

fun darkWineScheme() = darkColorScheme(
    primary = Color(0xFFFFB1C0),
    onPrimary = Color(0xFF5B0020),
    primaryContainer = WineDark,
    onPrimaryContainer = Rose,
    secondary = Color(0xFFE3BDC5),
    background = Color(0xFF171114),
    surface = Color(0xFF171114),
    surfaceVariant = Color(0xFF2A2023),
)
