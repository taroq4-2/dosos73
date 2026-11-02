package com.os73.dosos73

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Random
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * 100 deterministic fault/recovery runs against a real debug DocumentsProvider.
 * Object reconstruction models durable process-death boundaries; it does not kill
 * Android, fill the device disk, or certify an external/cloud provider.
 */
class StorageRecoverySoakTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val mime = "application/octet-stream"
    private val tree get() = DocumentsContract.buildTreeDocumentUri("com.os73.dosos73.recovery-tests", "root")
    private val scenarios = listOf("plan-before-create", "empty-unrecorded-create", "partial-recorded-copy",
        "complete-unrecorded-copy", "verified-before-publish", "rename-side-effect-then-error",
        "rejected-rename", "temporarily-unreadable", "ready-receipt-replay", "unknown-size-pipe")

    @Test fun oneHundredControlledRecoveriesPreserveHashesAndUnrelatedFiles() {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        val reportFile = File(context.filesDir, "readiness-storage-soak.json")
        val rows = JSONArray()
        val report = JSONObject().put("seed", 7301).put("expectedRuns", 100)
            .put("model", "in-process controlled provider faults and durable journal reconstruction")
            .put("notCovered", "OS process kill, reboot, true ENOSPC, real cloud/USB providers")
            .put("state", "running").put("runs", rows)
        reportFile.writeText(report.toString(2))
        context.grantUriPermission(context.packageName, tree, flags)
        try {
            repeat(100) { index ->
                report.put("currentRun", index + 1)
                reportFile.writeText(report.toString(2))
                val row = runCase(index)
                rows.put(row)
                reportFile.writeText(report.toString(2))
            }
            report.put("state", "passed").put("completedRuns", rows.length())
                .put("lostFixtureFiles", 0).put("corruptFixtureFiles", 0).put("duplicateOutputs", 0)
            reportFile.writeText(report.toString(2))
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "\nStorageRecoverySoak: 100/100 controlled runs passed; 0 lost/corrupt fixture files, 0 duplicate outputs. " +
                    "Evidence: files/readiness-storage-soak.json. No physical process-kill/ENOSPC claim.\n")
            })
        } finally {
            RecoveryTestDocumentsProvider.reset()
            context.revokeUriPermission(tree, flags)
        }
    }

    private fun runCase(index: Int): JSONObject {
        RecoveryTestDocumentsProvider.reset()
        val scenario = index % scenarios.size
        val seed = 7301L + index
        val bytes = ByteArray(4096 + (index * 1297) % (128 * 1024)).also { Random(seed).nextBytes(it) }
        val fingerprint = bytes.inputStream().use(StreamIntegrity::fingerprint)
        val expectedHash = hex(fingerprint)
        val id = UUID.randomUUID().toString()
        val source = File(context.cacheDir, "soak-source-$id.bin").apply { writeBytes(bytes) }
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, "root")
        val foreignBytes = "unrelated sibling $index".toByteArray()
        val foreign = DocumentsContract.createDocument(context.contentResolver, root, mime,
            "dosos73-neighbor-$id.partial.bin")!!
        context.contentResolver.openOutputStream(foreign)!!.use { it.write(foreignBytes) }
        var receipt = PublicationReceipt(context, id)
        try {
            var temporary = receipt.prepare(tree, mime, fingerprint, "fixture.bin", "bin")
            var staged: Uri? = null
            if (scenario != 0) {
                staged = DocumentsContract.createDocument(context.contentResolver, root, mime, temporary)!!
                if (scenario != 1 && scenario != 3) receipt.record(staged, mime, fingerprint, false)
                context.contentResolver.openOutputStream(staged)!!.use { output ->
                    when (scenario) {
                        1 -> Unit
                        2 -> output.write(bytes, 0, bytes.size / 2)
                        else -> output.write(bytes)
                    }
                }
            }
            // Recreate the receipt at every durable boundary instead of using cached state.
            receipt = PublicationReceipt(context, id)
            when (scenario) {
                0, 1, 2 -> {
                    assertNull("Incomplete run $index must restart safely", receipt.recover())
                    assertEquals(1, RecoveryTestDocumentsProvider.entries.size)
                    temporary = receipt.prepare(tree, mime, fingerprint, "fixture.bin", "bin")
                    staged = DocumentsContract.createDocument(context.contentResolver, root, mime, temporary)!!
                    receipt.record(staged, mime, fingerprint, false)
                    context.contentResolver.openOutputStream(staged)!!.use { it.write(bytes) }
                }
                4, 5, 6, 7, 8 -> receipt.markVerified()
            }
            when (scenario) {
                5 -> {
                    RecoveryTestDocumentsProvider.failAfterRename = true
                    assertTrue(runCatching { receipt.publishVerified(staged!!) }.isFailure)
                    RecoveryTestDocumentsProvider.failAfterRename = false
                }
                6 -> {
                    RecoveryTestDocumentsProvider.rejectRename = true
                    assertTrue(runCatching { receipt.publishVerified(staged!!) }.isFailure)
                    assertEquals(2, RecoveryTestDocumentsProvider.entries.size)
                    RecoveryTestDocumentsProvider.rejectRename = false
                }
                7 -> {
                    RecoveryTestDocumentsProvider.denyRead = true
                    assertTrue(runCatching { PublicationReceipt(context, id).recover() }.isFailure)
                    assertEquals(2, RecoveryTestDocumentsProvider.entries.size)
                    RecoveryTestDocumentsProvider.denyRead = false
                }
                8 -> receipt.publishVerified(staged!!)
                9 -> RecoveryTestDocumentsProvider.unknownSize = true
            }
            receipt = PublicationReceipt(context, id)
            val recovered = receipt.recover() ?: error("Run $index did not recover")
            val actual = context.contentResolver.openInputStream(recovered.first)!!.use(StreamIntegrity::fingerprint)
            StreamIntegrity.requireSame(fingerprint, actual)
            val firstRenames = RecoveryTestDocumentsProvider.renames
            assertEquals("Replay must return the same document", recovered.first,
                PublicationReceipt(context, id).recover()!!.first)
            assertEquals("Replay must not rename again", firstRenames, RecoveryTestDocumentsProvider.renames)
            assertEquals("Only one publication plus the unrelated sibling", 2, RecoveryTestDocumentsProvider.entries.size)
            context.contentResolver.openInputStream(foreign)!!.use { assertArrayEquals(foreignBytes, it.readBytes()) }
            source.inputStream().use { StreamIntegrity.requireSame(fingerprint, StreamIntegrity.fingerprint(it)) }
            assertEquals(1, RecoveryTestDocumentsProvider.renames)
            return JSONObject().put("run", index + 1).put("scenario", scenarios[scenario]).put("seed", seed)
                .put("bytes", bytes.size).put("sourceSha256", expectedHash).put("publishedSha256", hex(actual))
                .put("unrelatedPreserved", true).put("idempotentReplay", true).put("result", "passed")
        } finally {
            receipt.forget()
            source.delete()
            RecoveryTestDocumentsProvider.reset()
        }
    }

    private fun hex(value: StreamIntegrity.Fingerprint) = value.sha256.joinToString("") { "%02x".format(it) }
}
