package li.songe.gkd.bypass

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import li.songe.gkd.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Bypass Ads product UI components (style follows the original Bypass Ads shell:
 * warm off-white page, ink text, white rounded cards).
 */
object BypassPalette {
    val PageBackground = Color(0xFFF3F4F2)
    val Ink = Color(0xFF181A18)
    val Muted = Color(0xFF696D68)
    val Faint = Color(0xFF9CA09B)
    val SoftGreen = Color(0xFFE2F2E7)
    val SoftGray = Color(0xFFE9EBE8)
    val SoftRed = Color(0xFFFCE8E7)
    val Danger = Color(0xFFA8352B)
    val Accent = Color(0xFF2E7D4F)
}

@Composable
fun BypassSectionCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(8.dp))
            .padding(18.dp),
    ) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = BypassPalette.Ink)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
fun BypassMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(BypassPalette.SoftGray, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = BypassPalette.Ink)
        Text(label, modifier = Modifier.padding(top = 3.dp), fontSize = 11.sp, color = BypassPalette.Muted)
    }
}

@Composable
fun BypassModeButton(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        contentPadding = if (li.songe.gkd.i18n.UiLanguage.english) PaddingValues(horizontal = 10.dp, vertical = 8.dp) else ButtonDefaults.ContentPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) BypassPalette.Ink else BypassPalette.SoftGray,
            contentColor = if (selected) Color.White else BypassPalette.Ink,
        ),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier,
    ) {
        Text(label, maxLines = if (li.songe.gkd.i18n.UiLanguage.english) 2 else 1,
            fontSize = if (li.songe.gkd.i18n.UiLanguage.english) 12.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
            textAlign = TextAlign.Center)
    }
}

@Composable
fun BypassSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // A settings row is a single affordance. The full row target also
            // avoids a tiny hit area on large-screen and accessibility setups.
            .clickable { onCheckedChange(!checked) }
            .testTag("bypass-switch-$title")
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = BypassPalette.Ink)
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    modifier = Modifier.padding(top = 2.dp),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = BypassPalette.Muted,
                )
            }
        }
        Spacer(Modifier.size(12.dp))
        // The row owns the one semantic action. Giving Switch a second
        // callback causes some accessibility/pointer paths to toggle twice.
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = BypassPalette.Accent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = BypassPalette.Faint,
            ),
        )
    }
}

@Composable
fun BypassStatusCard(
    headline: String,
    description: String,
    healthy: Boolean,
    actionLabel: String?,
    onAction: (() -> Unit)?,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (healthy) BypassPalette.SoftGreen else Color.White, RoundedCornerShape(8.dp))
            .padding(18.dp),
    ) {
        Column {
            Text("运行状态", fontSize = 13.sp, color = BypassPalette.Muted)
            Text(
                headline,
                modifier = Modifier.padding(top = 7.dp),
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = BypassPalette.Ink,
            )
            Text(
                description,
                modifier = Modifier.padding(top = 6.dp),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = BypassPalette.Muted,
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = onAction,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BypassPalette.Ink,
                        contentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(8.dp),
                ) { Text(actionLabel) }
            }
        }
    }
}

@Composable
fun BypassNavRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = BypassPalette.Ink,
        )
        Text("›", fontSize = 22.sp, color = BypassPalette.Faint)
    }
}

@Composable
fun BypassAppRow(
    appName: String,
    packageName: String,
    icon: ImageBitmap?,
    enabled: Boolean,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .run { if (onClick != null) clickable(onClick = onClick) else this }
                .testTag("bypass-app-body-$packageName"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(BypassPalette.SoftGray, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (icon != null) {
                    Image(
                        bitmap = icon,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                    )
                } else {
                    Text(
                        appName.take(1).ifEmpty { "?" },
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BypassPalette.Muted,
                    )
                }
            }
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(appName, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = BypassPalette.Ink)
                Text(subtitle, modifier = Modifier.padding(top = 1.dp), fontSize = 11.sp, color = BypassPalette.Faint)
                Text(packageName, modifier = Modifier.padding(top = 1.dp), fontSize = 10.sp, color = BypassPalette.Faint)
            }
        }
        Spacer(Modifier.size(10.dp))
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            modifier = Modifier.testTag("bypass-app-switch-$packageName"),
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = BypassPalette.Accent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = BypassPalette.Faint,
            ),
        )
    }
}

@Composable
fun BypassMutedText(text: String, size: Int = 12, lineHeight: Int = 18, color: Color = BypassPalette.Muted) {
    Text(
        text,
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
        color = color,
    )
}

@Composable
fun BypassTitleBar(subtitle: String) {
    Column {
        Text("Bypass Ads", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = BypassPalette.Ink)
        Text(
            subtitle,
            modifier = Modifier.padding(top = 4.dp),
            fontSize = 13.sp,
            color = BypassPalette.Muted,
        )
    }
}

@Composable
fun BypassTabBar(
    tabs: List<Pair<String, BypassRootTab>>,
    selected: BypassRootTab,
    onSelect: (BypassRootTab) -> Unit,
) {
    Row {
        tabs.forEach { (label, tab) ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect(tab) }
                    .testTag("bypass-root-tab-$tab")
                    .padding(vertical = 10.dp),
            ) {
                Text(
                    text = label,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp,
                    fontWeight = if (selected == tab) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (selected == tab) BypassPalette.Ink else BypassPalette.Muted,
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(if (selected == tab) BypassPalette.Accent else Color.Transparent),
                )
            }
        }
    }
}
