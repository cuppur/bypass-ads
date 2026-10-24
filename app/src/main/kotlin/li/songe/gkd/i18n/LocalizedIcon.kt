package li.songe.gkd.i18n

import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector

@Composable
fun Icon(imageVector: ImageVector, contentDescription: String?, modifier: Modifier = Modifier,
         tint: Color = LocalContentColor.current) = MaterialIcon(
    imageVector, contentDescription?.let(UiLanguage::text), modifier, tint)

@Composable
fun Icon(painter: Painter, contentDescription: String?, modifier: Modifier = Modifier,
         tint: Color = LocalContentColor.current) = MaterialIcon(
    painter, contentDescription?.let(UiLanguage::text), modifier, tint)

@Composable
fun Icon(bitmap: ImageBitmap, contentDescription: String?, modifier: Modifier = Modifier,
         tint: Color = LocalContentColor.current) = MaterialIcon(
    bitmap, contentDescription?.let(UiLanguage::text), modifier, tint)
