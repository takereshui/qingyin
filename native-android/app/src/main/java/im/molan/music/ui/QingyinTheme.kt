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

private val Wine = Color(0xFF9D1F45)
private val WineDark = Color(0xFF541027)
private val Berry = Color(0xFF6F3D59)
private val Plum = Color(0xFF7B4267)
private val Rose = Color(0xFFFFD9E3)
private val Mist = Color(0xFFFFF6F8)

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
    onPrimaryContainer = Color(0xFF431021),
    secondary = Berry,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF9DFE8),
    onSecondaryContainer = Color(0xFF34101F),
    tertiary = Plum,
    tertiaryContainer = Color(0xFFF4DDEB),
    onTertiaryContainer = Color(0xFF321126),
    background = Mist,
    onBackground = Color(0xFF24181C),
    surface = Mist,
    onSurface = Color(0xFF24181C),
    surfaceVariant = Color(0xFFF3E5E9),
    onSurfaceVariant = Color(0xFF514047),
    outline = Color(0xFF8C707B),
    outlineVariant = Color(0xFFE3CDD5),
)

fun darkWineScheme() = darkColorScheme(
    primary = Color(0xFFFFB1C3),
    onPrimary = Color(0xFF650028),
    primaryContainer = WineDark,
    onPrimaryContainer = Rose,
    secondary = Color(0xFFEABACD),
    onSecondary = Color(0xFF472133),
    secondaryContainer = Color(0xFF5A3547),
    onSecondaryContainer = Color(0xFFFFD9E6),
    tertiary = Color(0xFFE9BDD9),
    tertiaryContainer = Color(0xFF59364D),
    onTertiaryContainer = Color(0xFFFFD8ED),
    background = Color(0xFF171114),
    onBackground = Color(0xFFEEDFE3),
    surface = Color(0xFF171114),
    onSurface = Color(0xFFEEDFE3),
    surfaceVariant = Color(0xFF302328),
    onSurfaceVariant = Color(0xFFD6C0C8),
    outline = Color(0xFFA38A93),
    outlineVariant = Color(0xFF514048),
)
