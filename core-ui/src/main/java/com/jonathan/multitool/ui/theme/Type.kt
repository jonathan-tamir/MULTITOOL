package com.jonathan.multitool.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jonathan.multitool.ui.R

val PlexSans = FontFamily(
    Font(R.font.ibmplexsans_regular, FontWeight.Normal),
    Font(R.font.ibmplexsans_semibold, FontWeight.SemiBold),
    Font(R.font.ibmplexsans_bold, FontWeight.Bold)
)

val PlexMono = FontFamily(
    Font(R.font.ibmplexmono_regular, FontWeight.Normal),
    Font(R.font.ibmplexmono_semibold, FontWeight.SemiBold)
)

/** All numerics / codes / metadata use mono, per the design brief. */
object Mono {
    val eyebrow = TextStyle(
        fontFamily = PlexMono, fontWeight = FontWeight.SemiBold,
        fontSize = 9.5.sp, lineHeight = 12.sp, letterSpacing = 1.6.sp
    )
    val label = TextStyle(
        fontFamily = PlexMono, fontWeight = FontWeight.Normal,
        fontSize = 10.5.sp, lineHeight = 13.sp
    )
    val labelMedium = TextStyle(
        fontFamily = PlexMono, fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp, lineHeight = 12.sp
    )
    val tag = TextStyle(
        fontFamily = PlexMono, fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 0.2.sp
    )
    val code = TextStyle(
        fontFamily = PlexMono, fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 3.sp
    )
}

private fun sans(size: Int, weight: FontWeight, line: Int, tracking: Float = 0f) = TextStyle(
    fontFamily = PlexSans, fontWeight = weight,
    fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.sp
)

val ShellTypography = Typography(
    displaySmall = sans(27, FontWeight.Bold, 30, -0.5f),
    headlineMedium = sans(25, FontWeight.Bold, 28, -0.5f),
    headlineSmall = sans(23, FontWeight.Bold, 26, -0.4f),
    titleLarge = sans(20, FontWeight.Bold, 24, -0.2f),
    titleMedium = sans(15, FontWeight.SemiBold, 19),
    titleSmall = sans(14, FontWeight.SemiBold, 18),
    bodyLarge = sans(14, FontWeight.Normal, 20),
    bodyMedium = sans(12, FontWeight.Normal, 18),
    bodySmall = sans(11, FontWeight.Normal, 16),
    labelLarge = sans(13, FontWeight.SemiBold, 16),
    labelMedium = sans(12, FontWeight.SemiBold, 15),
    labelSmall = sans(11, FontWeight.Normal, 14)
)
