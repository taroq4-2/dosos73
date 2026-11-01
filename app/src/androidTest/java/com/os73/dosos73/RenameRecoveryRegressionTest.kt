package com.os73.dosos73

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class RenameRecoveryRegressionTest {
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun isolated(block: suspend Fixture.() -> Unit) = runBlocking {
        val prefix = "rename-recovery-${UUID.randomUUID()}"
        val folder = File(base.cacheDir, prefix).apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = folder
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$prefix-$name", mode)
        }
        RecoveryTestDocumentsProvider.reset()
        try { Fixture(context).block() }
        finally {
            LibraryRepository.beforeIndexWriteForTests = null
            PrivateJournal.beforeWriteForTests = null
            RecoveryTestDocumentsProvider.reset()
            base.deleteSharedPreferences("$prefix-library")
            base.deleteSharedPreferences("$prefix-library_controls")
            folder.deleteRecursively()
            LibraryRepository.refresh(base)
        }
    }

    private class Fixture(val context: Context) {
        val tree: Uri = DocumentsContract.buildTreeDocumentUri("com.os73.dosos73.recovery-tests", "root")
        private val root = DocumentsContract.buildDocumentUriUsingTree(tree, "root")
        suspend fun create(name: String = "original.bin", content: String = "retained original bytes"): SavedFile {
            val uri = checkNotNull(DocumentsContract.createDocument(context.contentResolver, root, "application/octet-stream", name))
            checkNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(content.toByteArray()) }
            LibraryRepository.remember(context, uri, "application/octet-stream", name, content.toByteArray().size.toLong())
            return LibraryRepository.files.value.single { it.uri == uri.toString() }
        }

        suspend fun seedPreferences(item: SavedFile) {
            LibraryRepository.hideHistory(context, item)
            LibraryPreferences.setCollection(context, item.uri, "Keep collection")
        }

        fun assertContents(item: SavedFile, expected: String = "retained original bytes") {
            checkNotNull(context.contentResolver.openInputStream(Uri.parse(item.uri))).use {
                assertEquals(expected, it.bufferedReader().readText())
            }
        }

        suspend fun recover(original: SavedFile): SavedFile {
            // Recovery reads the disk index and plan, not the previous mutable URI snapshot.
            LibraryRepository.refresh(context)
            val changed = LibraryRepository.files.value.single {
                it.uri != original.uri && Uri.parse(it.uri).authority == tree.authority
            }
            assertFalse(LibraryRepository.files.value.any { it.uri == original.uri })
            assertEquals(setOf(changed.uri), LibraryRepository.hidden(context))
            assertEquals("Keep collection", LibraryPreferences.collection(context, changed.uri))
            assertEquals("", LibraryPreferences.collection(context, original.uri))
            assertNull(RenameRecovery(context).pending())
            assertContents(changed)
            return changed
        }
    }

    @Test fun indexWriteFailureRecoversReturnedUriAndPreferencesFromDurablePlan() = isolated {
        val original = create()
        seedPreferences(original)
        LibraryRepository.beforeIndexWriteForTests = { throw IOException("Injected index failure") }
        assertTrue(runCatching { LibraryRepository.rename(context, original, "renamed.bin") }.isFailure)
        assertNotNull(RenameRecovery(context).pending()!!.returned)
        val disk = File(context.filesDir, "saved-files.json").readText()
        assertTrue(disk.contains(original.uri))
        LibraryRepository.beforeIndexWriteForTests = null
        val changed = recover(original)
        assertEquals("renamed.bin", changed.name)
        assertEquals(1, RecoveryTestDocumentsProvider.renames)
        assertEquals(0, RecoveryTestDocumentsProvider.deletes)
        LibraryRepository.refresh(context)
        assertEquals(1, LibraryRepository.files.value.count { it.uri == changed.uri })
    }

    @Test fun missingReturnedUriWriteRecoversUniqueRenamedDocumentWithoutSecondRename() = isolated {
        val original = create()
        seedPreferences(original)
        var writes = 0
        PrivateJournal.beforeWriteForTests = { file ->
            if (file.name == "library-rename.json" && ++writes == 2) throw IOException("Lost returned URI receipt")
        }
        assertTrue(runCatching { LibraryRepository.rename(context, original, "renamed.bin") }.isFailure)
        PrivateJournal.beforeWriteForTests = null
        assertNull(RenameRecovery(context).pending()!!.returned)
        recover(original)
        assertEquals(1, RecoveryTestDocumentsProvider.renames)
        assertEquals(0, RecoveryTestDocumentsProvider.deletes)
    }

    @Test fun providerRenamedThenThrewRecoversFromPreparedIntent() = isolated {
        val original = create()
        seedPreferences(original)
        RecoveryTestDocumentsProvider.failAfterRename = true
        assertTrue(runCatching { LibraryRepository.rename(context, original, "renamed.bin") }.isFailure)
        RecoveryTestDocumentsProvider.failAfterRename = false
        assertNull(RenameRecovery(context).pending()!!.returned)
        recover(original)
        assertEquals(1, RecoveryTestDocumentsProvider.renames)
        assertEquals(0, RecoveryTestDocumentsProvider.deletes)
    }

    @Test fun interruptedBeforeProviderMutationRetainsOriginalWithoutRenamingOnRecovery() = isolated {
        val original = create()
        seedPreferences(original)
        RenameRecovery(context).prepare(original, "renamed.bin", true, "Keep collection")
        LibraryRepository.refresh(context)
        assertTrue(LibraryRepository.files.value.any { it.uri == original.uri })
        assertNull(RenameRecovery(context).pending())
        assertEquals(setOf(original.uri), LibraryRepository.hidden(context))
        assertContents(original)
        assertEquals(0, RecoveryTestDocumentsProvider.renames)
    }

    @Test fun failedIntentWriteNeverChangesProvider() = isolated {
        val original = create()
        PrivateJournal.beforeWriteForTests = { file ->
            if (file.name == "library-rename.json") throw IOException("No durable intent")
        }
        assertTrue(runCatching { LibraryRepository.rename(context, original, "renamed.bin") }.isFailure)
        PrivateJournal.beforeWriteForTests = null
        assertNull(RenameRecovery(context).pending())
        assertEquals(0, RecoveryTestDocumentsProvider.renames)
        assertContents(original)
    }

    @Test fun collectionCommitFailureIsReplayedWithoutRepeatingProviderRename() = isolated {
        val original = create()
        seedPreferences(original)
        val failing = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val preferences = context.getSharedPreferences(name, mode)
                return if (name == "library_controls") object : SharedPreferences by preferences {
                    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor by preferences.edit() {
                        override fun remove(key: String?): SharedPreferences.Editor = this
                        override fun putString(key: String?, value: String?): SharedPreferences.Editor = this
                        override fun commit() = false
                    }
                } else preferences
            }
        }
        assertTrue(runCatching { LibraryRepository.rename(failing, original, "renamed.bin") }.isFailure)
        assertNotNull(RenameRecovery(context).pending())
        recover(original)
        assertEquals(1, RecoveryTestDocumentsProvider.renames)
    }

    @Test fun ambiguousIdenticalNewDocumentsPreserveIntentAndAllFiles() = isolated {
        val original = create()
        val recovery = RenameRecovery(context)
        recovery.prepare(original, "renamed.bin", false, "")
        DocumentsContract.renameDocument(context.contentResolver, Uri.parse(original.uri), "renamed.bin")
        // A second indistinguishable document must never be guessed as the renamed file.
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, "root")
        val duplicate = checkNotNull(DocumentsContract.createDocument(context.contentResolver, root, original.mime, "renamed.bin"))
        checkNotNull(context.contentResolver.openOutputStream(duplicate)).use { it.write("retained original bytes".toByteArray()) }
        assertTrue(runCatching { LibraryRepository.refresh(context) }.isFailure)
        assertNotNull(recovery.pending())
        assertEquals(2, RecoveryTestDocumentsProvider.entries.size)
        assertEquals(0, RecoveryTestDocumentsProvider.deletes)
    }

    @Test fun preexistingIdenticalSiblingCannotReplaceTheLostOriginal() = isolated {
        val original = create()
        val sibling = create("renamed.bin")
        val recovery = RenameRecovery(context)
        recovery.prepare(original, "renamed.bin", false, "")
        DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(original.uri))
        assertTrue(runCatching { LibraryRepository.refresh(context) }.isFailure)
        assertNotNull(recovery.pending())
        assertContents(sibling)
        assertTrue(LibraryRepository.files.value.any { it.uri == original.uri })
        assertEquals(1, RecoveryTestDocumentsProvider.deletes) // Explicit fixture deletion only.
    }

    @Test fun changedContentKeepsRecordedUriAndIntentForReview() = isolated {
        val original = create()
        LibraryRepository.beforeIndexWriteForTests = { throw IOException("Injected index failure") }
        assertTrue(runCatching { LibraryRepository.rename(context, original, "renamed.bin") }.isFailure)
        LibraryRepository.beforeIndexWriteForTests = null
        val plan = checkNotNull(RenameRecovery(context).pending())
        val returned = checkNotNull(plan.returned)
        checkNotNull(context.contentResolver.openOutputStream(Uri.parse(returned))).use { it.write("externally changed".toByteArray()) }
        assertTrue(runCatching { LibraryRepository.refresh(context) }.isFailure)
        assertEquals(returned, RenameRecovery(context).pending()!!.returned)
        assertEquals(1, RecoveryTestDocumentsProvider.entries.size)
        assertEquals(0, RecoveryTestDocumentsProvider.deletes)
    }

    @Test fun normalizedNameIsRecoveredOnlyWithRecordedReturnedIdentity() = isolated {
        val original = create()
        val recovery = RenameRecovery(context)
        val plan = recovery.prepare(original, "requested.bin", false, "")
        val returned = checkNotNull(DocumentsContract.renameDocument(context.contentResolver, Uri.parse(original.uri), "provider-normalized.bin"))
        assertTrue(runCatching { recovery.resolve(plan) }.isFailure)
        val recorded = recovery.recordReturned(plan, returned)
        assertEquals("provider-normalized.bin", checkNotNull(recovery.resolve(recorded)).name)
        LibraryRepository.refresh(context)
        assertNull(recovery.pending())
        assertEquals(0, RecoveryTestDocumentsProvider.deletes)
    }

    @Test fun stableDocumentIdentityKeepsItsExistingCollectionAndHiddenDecision() = isolated {
        val original = create()
        seedPreferences(original)
        val recovery = RenameRecovery(context)
        val plan = recovery.prepare(original, "renamed.bin", true, "Keep collection")
        val id = DocumentsContract.getDocumentId(Uri.parse(original.uri))
        RecoveryTestDocumentsProvider.entries.compute(id) { _, entry -> checkNotNull(entry).copy(name = "renamed.bin") }
        recovery.recordReturned(plan, Uri.parse(original.uri))
        LibraryRepository.refresh(context)
        assertEquals("renamed.bin", LibraryRepository.files.value.single { it.uri == original.uri }.name)
        assertEquals("Keep collection", LibraryPreferences.collection(context, original.uri))
        assertEquals(setOf(original.uri), LibraryRepository.hidden(context))
        assertNull(recovery.pending())
        assertContents(original)
    }

    @Test fun pendingOfflineRenameKeepsColdLibraryAndNewPublicationsAvailable() = isolated {
        val original = create()
        val unrelated = create("unrelated.bin", "unrelated bytes")
        val recovery = RenameRecovery(context)
        recovery.prepare(original, "renamed.bin", false, "")
        RecoveryTestDocumentsProvider.denyRead = true
        // Model a fresh process: the public flow contains no remembered library snapshot.
        @Suppress("UNCHECKED_CAST")
        val state = LibraryRepository::class.java.getDeclaredField("mutable").apply { isAccessible = true }
            .get(LibraryRepository) as kotlinx.coroutines.flow.MutableStateFlow<List<SavedFile>>
        state.value = emptyList()
        assertTrue(runCatching { LibraryRepository.refresh(context) }.isFailure)
        assertTrue(LibraryRepository.files.value.any { it.uri == unrelated.uri })
        assertNotNull(recovery.pending())
        // Creation/publication of another document must remain durably indexed.
        val published = create("new-publication.bin", "new bytes")
        assertTrue(File(context.filesDir, "saved-files.json").readText().contains(published.uri))
        assertNotNull(recovery.pending())
        assertTrue(runCatching { LibraryRepository.delete(context, original) }.isFailure)
        LibraryRepository.hideHistory(context, unrelated)
        LibraryRepository.delete(context, unrelated)
        assertFalse(LibraryRepository.files.value.any { it.uri == unrelated.uri })
        assertNotNull(recovery.pending())
        RecoveryTestDocumentsProvider.denyRead = false
        LibraryRepository.refresh(context)
        assertNull(recovery.pending())
        assertTrue(LibraryRepository.files.value.any { it.uri == published.uri })
        assertContents(original)
        assertContents(published, "new bytes")
    }

    @Test fun returnedIdentityIsValidatedBeforeReceiptOrPublicStateChanges() = isolated {
        val original = create()
        val sibling = create("sibling.bin")
        val recovery = RenameRecovery(context)
        val plan = recovery.prepare(original, "renamed.bin", false, "")
        val before = PrivateJournal(context, "library-rename.json").readOrNull()
        for (untrusted in listOf(
            Uri.parse("content://unrelated.provider/tree/root/document/unknown"),
            DocumentsContract.buildDocumentUriUsingTree(
                DocumentsContract.buildTreeDocumentUri(tree.authority!!, "elsewhere"), "unknown"),
            Uri.parse(sibling.uri),
        )) {
            assertTrue(runCatching { recovery.validateReturned(plan, untrusted) }.isFailure)
            assertTrue(runCatching { recovery.recordReturned(plan, untrusted) }.isFailure)
            assertEquals(before, PrivateJournal(context, "library-rename.json").readOrNull())
            assertTrue(LibraryRepository.files.value.any { it.uri == original.uri })
        }
        assertContents(original)
        assertContents(sibling)
    }

    @Test fun cancellationDuringFingerprintOpenReleasesTheLibraryWithoutRenaming() = isolated {
        val original = create()
        val unrelated = create("unrelated.bin")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        RecoveryTestDocumentsProvider.onRead = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "Fingerprint gate timed out" }
        }
        coroutineScope {
            val rename = async(Dispatchers.Default) { LibraryRepository.rename(context, original, "renamed.bin") }
            try {
                assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                withTimeout(3000) { rename.cancelAndJoin() }
            } finally {
                release.countDown()
                RecoveryTestDocumentsProvider.onRead = null
                rename.cancelAndJoin()
            }
        }
        assertEquals(0, RecoveryTestDocumentsProvider.renames)
        assertNull(RenameRecovery(context).pending())
        withTimeout(3000) { LibraryRepository.hideHistory(context, unrelated) }
        assertContents(original)
    }
}
