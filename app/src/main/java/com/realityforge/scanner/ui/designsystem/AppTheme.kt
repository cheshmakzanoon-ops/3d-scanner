package com.realityforge.scanner.ui.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

private val Light = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF006B5E),
    onPrimary = androidx.compose.ui.graphics.Color.White,
    secondary = androidx.compose.ui.graphics.Color(0xFF515F5B),
    background = androidx.compose.ui.graphics.Color(0xFFF6FBF8),
    onBackground = androidx.compose.ui.graphics.Color(0xFF17211D),
    surface = androidx.compose.ui.graphics.Color(0xFFF6FBF8),
    onSurface = androidx.compose.ui.graphics.Color(0xFF17211D),
    error = androidx.compose.ui.graphics.Color(0xFFBA1A1A),
)
private val Dark = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF73D8C7),
    onPrimary = androidx.compose.ui.graphics.Color(0xFF00382F),
    secondary = androidx.compose.ui.graphics.Color(0xFFBBCBC4),
    background = androidx.compose.ui.graphics.Color(0xFF101A16),
    onBackground = androidx.compose.ui.graphics.Color(0xFFE0EAE3),
    surface = androidx.compose.ui.graphics.Color(0xFF101A16),
    onSurface = androidx.compose.ui.graphics.Color(0xFFE0EAE3),
    error = androidx.compose.ui.graphics.Color(0xFFFFB4AB),
)

@Composable
fun AppTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, content = content)
}

/** The caller owns scrolling; bounded width leaves large text enough vertical room. */
@Composable
fun ScreenFrame(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = LayoutPolicy.MaximumContentWidthDp.dp).fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

enum class EvidenceTone { Neutral, Partial, Error }

@Composable
fun EvidenceCard(title: String, body: String, modifier: Modifier = Modifier, tone: EvidenceTone = EvidenceTone.Neutral) {
    val container = when (tone) {
        EvidenceTone.Neutral -> MaterialTheme.colorScheme.surfaceContainer
        EvidenceTone.Partial -> MaterialTheme.colorScheme.secondaryContainer
        EvidenceTone.Error -> MaterialTheme.colorScheme.errorContainer
    }
    val foreground = when (tone) {
        EvidenceTone.Neutral -> MaterialTheme.colorScheme.onSurface
        EvidenceTone.Partial -> MaterialTheme.colorScheme.onSecondaryContainer
        EvidenceTone.Error -> MaterialTheme.colorScheme.onErrorContainer
    }
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container, contentColor = foreground)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** One labelled semantics node and one toggle action, including its full 48dp target. */
@Composable
fun LabelledToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(modifier.fillMaxWidth().heightIn(min = LayoutPolicy.MinimumTargetDp.dp)
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
