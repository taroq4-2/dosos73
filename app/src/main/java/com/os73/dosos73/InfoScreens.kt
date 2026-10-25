package com.os73.dosos73

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable internal fun AboutScreen(back: () -> Unit, contact: () -> Unit) {
    val context = LocalContext.current
    var noHandler by remember { mutableStateOf(false) }
    fun browse(url: String) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: ActivityNotFoundException) { noHandler = true }
    }
    Page {
        TextButton(back) { Text(stringResource(R.string.back)) }
        Brand()
        Text(stringResource(R.string.about), style = MaterialTheme.typography.headlineMedium)
        OutlinedCard(shape = RoundedCornerShape(20.dp)) {
            Text(stringResource(R.string.about_description), Modifier.padding(20.dp), style = MaterialTheme.typography.bodyLarge)
        }
        OutlinedButton({ browse("https://github.com/taroq4-2/dosos73") }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.github_project)) }
        OutlinedButton({ browse("https://os73.com") }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.developer_site)) }
        TextButton({ browse("https://github.com/taroq4-2/dosos73/blob/main/THIRD_PARTY_NOTICES.md") }) { Text(stringResource(R.string.licenses)) }
        Text(stringResource(R.string.preview_status), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(contact, Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("contact"), shape = RoundedCornerShape(18.dp)) { Text(stringResource(R.string.contact)) }
        if (noHandler) Text(stringResource(R.string.error_open), color = MaterialTheme.colorScheme.error)
        Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable internal fun ContactScreen(back: () -> Unit) {
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var subject by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf(0) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val draft = ContactDraft(name, email, subject, message)
    Page {
        TextButton(back, Modifier.testTag("back")) { Text(stringResource(R.string.back)) }
        Text(stringResource(R.string.contact), style = MaterialTheme.typography.headlineMedium)
        Text("contact@os73.com", color = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.contact_notice), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(name, { name = it.take(120); status = 0 }, Modifier.fillMaxWidth().testTag("contact_name"),
            label = { Text(stringResource(R.string.contact_name)) }, singleLine = true, shape = RoundedCornerShape(16.dp))
        OutlinedTextField(email, { email = it.take(254); status = 0 }, Modifier.fillMaxWidth().testTag("contact_email"),
            label = { Text(stringResource(R.string.contact_email)) }, singleLine = true, shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        OutlinedTextField(subject, { subject = it.replace("\n", " ").replace("\r", " ").take(160); status = 0 }, Modifier.fillMaxWidth().testTag("contact_subject"),
            label = { Text(stringResource(R.string.contact_subject)) }, singleLine = true, shape = RoundedCornerShape(16.dp))
        OutlinedTextField(message, { message = it.take(5000); status = 0 }, Modifier.fillMaxWidth().testTag("contact_message"),
            label = { Text(stringResource(R.string.contact_message)) }, minLines = 4, maxLines = 8, shape = RoundedCornerShape(16.dp))
        if (attempted && !draft.valid) Text(stringResource(R.string.contact_invalid), color = MaterialTheme.colorScheme.error)
        Button({
            attempted = true
            if (draft.valid) {
                val uri = Uri.parse("mailto:contact@os73.com?subject=${Uri.encode(subject.trim())}&body=${Uri.encode(draft.body())}")
                try { context.startActivity(Intent(Intent.ACTION_SENDTO, uri)); status = R.string.composer_opened }
                catch (_: ActivityNotFoundException) { status = R.string.no_email_app }
            }
        }, Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("compose_email"), shape = RoundedCornerShape(18.dp)) { Text(stringResource(R.string.compose_email)) }
        OutlinedButton({
            context.getSystemService(Context.CLIPBOARD_SERVICE).let { it as ClipboardManager }
                .setPrimaryClip(ClipData.newPlainText("Contact OS73", draft.copyText()))
            status = R.string.message_copied
        }, Modifier.fillMaxWidth().testTag("copy_message")) { Text(stringResource(R.string.copy_message)) }
        if (status != 0) Text(stringResource(status), style = MaterialTheme.typography.bodyMedium)
    }
}
