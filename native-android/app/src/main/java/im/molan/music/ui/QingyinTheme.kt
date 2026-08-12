package im.molan.music.ui

import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

private val Wine = Color(0xFF8C1636)
private val WineDark = Color(0xFF5A0620)
private val Rose = Color(0xFFFFD9E0)

/** 统一标题、列表和说明文字的字号与行高，避免页面间视觉密度不一致。 */
val qingyinTypography = Typography(
    headlineMedium = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 25.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
)

/** 统一容器曲率：常规行项目、小卡片和大面板分别使用递进圆角。 */
val qingyinShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(34.dp),
)

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
