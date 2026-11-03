package com.os73.dosos73

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/** Stateless review UI; the screen owns task snapshots, permission requests and cancellation. */
@Composable
internal fun QuickDownloadReview(
    screen: ScreenState, customFolder: Boolean, startEnabled: Boolean,
    onChoice: (Choice) -> Unit, onFolder: () -> Unit, onDefaultFolder: () -> Unit,
    onPreset: (Choice, String?) -> Unit, onStart: () -> Unit, onClose: () -> Unit,
) {
    val compact = LocalConfiguration.current.screenHeightDp < 420
    AlertDialog(
        modifier = compactReviewModifier(compact).testTag("quick_dialog"),
        properties = DialogProperties(usePlatformDefaultWidth = !compact),
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.quick_entry_label),
            maxLines = if (compact) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(screen.url, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = Neon.Secondary)
                if (screen.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                screen.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                AnalysisOptions(screen, customFolder, onChoice, onFolder, onDefaultFolder, onPreset)
            }
        },
        confirmButton = {
            TextButton(onStart, enabled = startEnabled && !screen.busy && screen.selected != null,
                modifier = Modifier.testTag("quick_start")) {
                Symbol(Glyph.DOWNLOAD)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.start_short))
            }
        },
        dismissButton = { TextButton(onClose, Modifier.testTag("quick_close")) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun DownloadHistoryReview(item: SavedFile, onAnswer: (Int) -> Unit) {
    val compact = LocalConfiguration.current.screenHeightDp < 420
    AlertDialog(
        modifier = compactReviewModifier(compact).testTag("history_dialog"),
        properties = DialogProperties(usePlatformDefaultWidth = !compact),
        onDismissRequest = { onAnswer(0) },
        title = { Text(stringResource(R.string.history_downloaded_before),
            maxLines = if (compact) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.history_downloaded_before_message))
                Text(item.name, maxLines = 3, overflow = TextOverflow.Ellipsis)
                TextButton({ onAnswer(2) }, Modifier.testTag("history_open")) {
                    Text(stringResource(R.string.history_open_existing))
                }
            }
        },
        confirmButton = { TextButton({ onAnswer(1) }, Modifier.testTag("history_again")) {
            Text(stringResource(R.string.history_download_again))
        } },
        dismissButton = { TextButton({ onAnswer(0) }, Modifier.testTag("history_cancel")) {
            Text(stringResource(R.string.cancel))
        } },
    )
}

@Composable
internal fun TransferDetailsDialog(transfer: TransferState, onClose: () -> Unit) {
    val compact = LocalConfiguration.current.screenHeightDp < 420
    AlertDialog(
        modifier = compactReviewModifier(compact).testTag("transfer_details_dialog"),
        properties = DialogProperties(usePlatformDefaultWidth = !compact),
        onDismissRequest = onClose,
        title = { Text(transfer.title, maxLines = if (compact) 1 else 3, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(phaseText(transfer.phase)))
                Text(transfer.detail)
                Text(bytesLabel(transfer.bytes, LocalConfiguration.current.locales[0]))
                Text(stringResource(R.string.pause_explanation), color = Neon.Secondary)
            }
        },
        confirmButton = { TextButton(onClose, Modifier.testTag("transfer_details_close")) {
            Text(stringResource(R.string.close))
        } },
    )
}

private fun compactReviewModifier(compact: Boolean): Modifier =
    if (compact) Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(vertical = 24.dp) else Modifier

@Composable
internal fun PendingSharedReview(screen: ScreenState, batchBusy: Boolean, batchSaving: Boolean,
    batchStorageError: Boolean, onReview: () -> Unit, onDiscard: () -> Unit) {
    // When the ViewModel cannot accept the link, do not cover the draft retry UI.
    if (screen.pendingShared == null || screen.quick || screen.busy || batchBusy || batchSaving || batchStorageError) return
    AlertDialog(
        modifier = Modifier.testTag("pending_share_dialog"),
        properties = DialogProperties(dismissOnClickOutside = false),
        onDismissRequest = onDiscard,
        title = { Text(stringResource(R.string.pending_link)) },
        text = { Text(stringResource(R.string.pending_link_hint)) },
        confirmButton = {
            TextButton(onReview, Modifier.testTag("pending_share_review")) { Text(stringResource(R.string.batch_review)) }
        },
        dismissButton = {
            TextButton(onDiscard, Modifier.testTag("pending_share_discard")) { Text(stringResource(R.string.cancel)) }
        },
    )
}
