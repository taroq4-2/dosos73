package com.os73.dosos73

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.text.DateFormat
import java.util.Date

@Composable
internal fun FileDetailsContent(item: SavedFile) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var digest by remember(item.uri) { mutableStateOf<FileDigest.Result?>(null) }
    var expected by rememberSaveable(item.uri) { mutableStateOf("") }
    var running by remember(item.uri) { mutableStateOf<Job?>(null) }
    var hashing by remember(item.uri) { mutableStateOf(false) }
    var failed by remember(item.uri) { mutableStateOf(false) }
    var copied by remember(item.uri) { mutableStateOf(false) }
    Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(item.name)
        Text(bytesLabel(item.bytes, LocalConfiguration.current.locales[0]))
        Text(item.mime)
        Text(DateFormat.getDateTimeInstance().format(Date(item.date)))
        HorizontalDivider()
        Text(stringResource(R.string.sha256_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.sha256_hint), style = MaterialTheme.typography.bodySmall, color = Neon.Secondary)
        if (hashing) {
            LinearProgressIndicator(Modifier.fillMaxWidth().testTag("hash_running"))
            TextButton({ running?.cancel() }) { Text(stringResource(R.string.cancel)) }
        } else OutlinedButton({
            failed = false; copied = false; digest = null; hashing = true
            running = scope.launch {
                try { digest = FileDigest.compute(context, Uri.parse(item.uri)) }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { failed = true }
                finally { hashing = false }
            }
        }, Modifier.fillMaxWidth().testTag("calculate_hash")) { Text(stringResource(R.string.calculate_sha256)) }
        if (failed) Text(stringResource(R.string.sha256_failed), color = MaterialTheme.colorScheme.error)
        digest?.let { actual ->
            SelectionContainer {
                Text(actual.sha256, Modifier.testTag("file_sha256"), fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
            }
            Text(bytesLabel(actual.bytes, LocalConfiguration.current.locales[0]), style = MaterialTheme.typography.bodySmall)
            TextButton({
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("SHA-256", actual.sha256))
                copied = true
            }) { Text(stringResource(if (copied) R.string.sha256_copied else R.string.copy_sha256)) }
            OutlinedTextField(expected, { expected = it.take(128) },
                label = { Text(stringResource(R.string.expected_sha256)) },
                textStyle = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                modifier = Modifier.fillMaxWidth().testTag("expected_hash"), singleLine = true)
            if (expected.isNotBlank()) {
                val normalized = FileDigest.normalizedExpected(expected)
                val matches = normalized == actual.sha256
                Text(stringResource(when { normalized == null -> R.string.sha256_invalid; matches -> R.string.sha256_matches; else -> R.string.sha256_differs }),
                    color = if (matches) Neon.Cyan else MaterialTheme.colorScheme.error)
            }
        }
    }
}
