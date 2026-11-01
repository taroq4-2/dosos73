package com.os73.dosos73

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun QuickLaunchScreen(
    enabled: Boolean,
    launcherPaste: Boolean,
    onEnabled: (Boolean) -> Unit,
    onLauncherPaste: (Boolean) -> Unit,
    onSetup: () -> Unit,
    onPin: () -> Unit,
    onTry: () -> Unit,
) {
    Page {
        Brand(compact = true)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(R.drawable.ic_quick_download),
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = Neon.Cyan,
            )
            Text(
                stringResource(R.string.quick_title),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineMedium,
            )
        }
        Panel {
            QuickLaunchToggle(
                enabled,
                onEnabled,
                stringResource(R.string.quick_enable),
                Modifier.testTag("quick_enable"),
            )
            Text(stringResource(R.string.quick_explanation), color = Neon.Secondary)
        }
        Panel {
            Text(
                stringResource(R.string.quick_setup_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(stringResource(R.string.quick_oem_hint), color = Neon.Secondary)
            Text(stringResource(R.string.quick_step_one))
            Text(stringResource(R.string.quick_step_two))
            Text(stringResource(R.string.quick_step_three))
            OutlinedButton(
                onClick = onSetup,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("quick_setup"),
            ) {
                Symbol(Glyph.SETTINGS)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.quick_setup), Modifier.weight(1f))
            }
            OutlinedButton(
                onClick = onPin,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("quick_pin"),
            ) {
                Symbol(Glyph.LINK)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.quick_pin), Modifier.weight(1f))
            }
            Text(stringResource(R.string.quick_pin_hint), color = Neon.Secondary)
        }
        Panel {
            QuickLaunchToggle(
                launcherPaste,
                onLauncherPaste,
                stringResource(R.string.quick_fallback),
                Modifier.testTag("quick_fallback"),
                available = enabled,
            )
            Text(stringResource(R.string.quick_fallback_hint), color = Neon.Secondary)
        }
        GradientButton(
            stringResource(R.string.quick_try),
            onTry,
            Modifier.fillMaxWidth().testTag("quick_try"),
            enabled = enabled,
            icon = Glyph.DOWNLOAD,
        )
        Text(
            stringResource(R.string.quick_try_hint),
            style = MaterialTheme.typography.bodySmall,
            color = Neon.Secondary,
        )
    }
}

@Composable
private fun QuickLaunchToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    available: Boolean = true,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(
                value = checked,
                enabled = available,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Switch(checked = checked, onCheckedChange = null, enabled = available)
    }
}
