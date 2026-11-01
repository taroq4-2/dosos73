package com.os73.dosos73

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class LibraryMutationTest {
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun failedNameQueryAfterRenameRetainsReturnedUriAndContents() = runBlocking {
        withFixture { fixture ->
            val original = fixture.createFile()
            LibraryPreferences.setCollection(fixture.context, original.uri, "Retained collection")
            RecoveryTestDocumentsProvider.denyQuery = true
            try {
                LibraryRepository.rename(fixture.context, original, "renamed.bin")
            } finally {
                RecoveryTestDocumentsProvider.denyQuery = false
            }
            val renamed = LibraryRepository.files.value.single()
            assertNotEquals(original.uri, renamed.uri)
            assertEquals("renamed.bin", renamed.name)
            assertEquals("Retained collection", LibraryPreferences.collection(fixture.context, renamed.uri))
            fixture.assertContents(renamed)
            // A fresh read of the private index must retain the provider's new document ID.
            LibraryRepository.refresh(fixture.context)
            assertEquals(renamed, LibraryRepository.files.value.single { it.uri == renamed.uri })
            assertFalse(LibraryRepository.files.value.any { it.uri == original.uri })
            fixture.assertContents(renamed)
        }
    }

    @Test fun failedCollectionMigrationKeepsRenamedReferenceDurableAndHidden() = runBlocking {
        withFixture { fixture ->
            val original = fixture.createFile()
            LibraryPreferences.setCollection(fixture.context, original.uri, "Collection before failure")
            LibraryRepository.hideHistory(fixture.context, original)
            val failingContext = object : ContextWrapper(fixture.context) {
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                    val preferences = fixture.context.getSharedPreferences(name, mode)
                    return if (name == "library_controls") object : SharedPreferences by preferences {
                        override fun edit(): SharedPreferences.Editor = FailingEditor(preferences.edit())
                    } else preferences
                }
            }
            assertTrue(runCatching {
                LibraryRepository.rename(failingContext, original, "renamed.bin")
            }.isFailure)
            val renamed = LibraryRepository.files.value.single()
            assertNotEquals(original.uri, renamed.uri)
            assertEquals(setOf(renamed.uri), LibraryRepository.hidden(fixture.context))
            LibraryRepository.refresh(fixture.context)
            assertTrue(LibraryRepository.files.value.any { it.uri == renamed.uri })
            assertFalse(LibraryRepository.files.value.any { it.uri == original.uri })
            fixture.assertContents(renamed)
        }
    }

    @Test fun hiddenDocumentStaysHiddenAfterUriChangingRename() = runBlocking {
        withFixture { fixture ->
            val original = fixture.createFile()
            LibraryRepository.hideHistory(fixture.context, original)
            fixture.assertHiddenRename(original)
        }
    }

    @Test fun clearedHistoryStaysHiddenAfterUriChangingRename() = runBlocking {
        withFixture { fixture ->
            val original = fixture.createFile()
            LibraryRepository.clearHistory(fixture.context)
            fixture.assertHiddenRename(original)
        }
    }

    @Test fun concurrentHidesRetainBothItemsAndExistingHistory() = runBlocking {
        withFixture { fixture ->
            val first = fixture.createFile()
            val second = first.copy(uri = "content://fixture/second")
            val prior = "content://fixture/prior"
            fixture.seedHidden(prior)
            assertConcurrentUnion(fixture, first, setOf(prior, first.uri, second.uri)) {
                LibraryRepository.hideHistory(fixture.context, second)
            }
        }
    }

    @Test fun concurrentHideAndClearRetainTheirUnion() = runBlocking {
        withFixture { fixture ->
            val indexed = fixture.createFile()
            val separatelyHidden = indexed.copy(uri = "content://fixture/separately-hidden")
            val prior = "content://fixture/prior"
            fixture.seedHidden(prior)
            assertConcurrentUnion(fixture, separatelyHidden, setOf(prior, indexed.uri, separatelyHidden.uri)) {
                LibraryRepository.clearHistory(fixture.context)
            }
        }
    }

    /**
     * Hold the first read after it captured its snapshot. Without serialization the second
     * mutation can commit first, exposing a lost update when the first is released.
     * With the repository mutex the second read waits, then sees the first committed set.
     */
    private suspend fun assertConcurrentUnion(
        fixture: Fixture,
        firstItem: SavedFile,
        expected: Set<String>,
        secondAction: suspend () -> Unit,
    ) = coroutineScope {
        fixture.gate.arm()
        val first = async { LibraryRepository.hideHistory(fixture.context, firstItem) }
        var second: Deferred<Unit>? = null
        try {
            assertTrue("First history read did not start", withContext(Dispatchers.IO) {
                fixture.gate.firstRead.await(5, TimeUnit.SECONDS)
            })
            second = async { secondAction() }
            val overlapped = withContext(Dispatchers.IO) {
                fixture.gate.secondRead.await(500, TimeUnit.MILLISECONDS)
            }
            if (overlapped) checkNotNull(second).await()
        } finally {
            fixture.gate.release.countDown()
            fixture.gate.armed = false
        }
        first.await()
        checkNotNull(second).await()
        assertEquals(expected, LibraryRepository.hidden(fixture.context))
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val id = "library-mutation-" + UUID.randomUUID()
        val folder = File(base.cacheDir, id).apply { check(mkdirs()) }
        val historyPrefs = base.getSharedPreferences("$id-library", Context.MODE_PRIVATE)
        val controlsPrefs = base.getSharedPreferences("$id-library_controls", Context.MODE_PRIVATE)
        val gate = HistoryReadGate(historyPrefs)
        val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = folder
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "library") gate else base.getSharedPreferences("$id-$name", mode)
        }
        RecoveryTestDocumentsProvider.reset()
        try {
            block(Fixture(context, gate))
        } finally {
            gate.release.countDown()
            gate.armed = false
            RecoveryTestDocumentsProvider.reset()
            historyPrefs.edit().clear().commit()
            controlsPrefs.edit().clear().commit()
            folder.deleteRecursively()
            LibraryRepository.refresh(base)
        }
    }

    private class Fixture(val context: Context, val gate: HistoryReadGate) {
        private val contents = "retained-content"

        suspend fun createFile(): SavedFile {
            val tree = DocumentsContract.buildTreeDocumentUri("com.os73.dosos73.recovery-tests", "root")
            val root = DocumentsContract.buildDocumentUriUsingTree(tree, "root")
            val uri = checkNotNull(DocumentsContract.createDocument(
                context.contentResolver, root, "application/octet-stream", "original.bin",
            ))
            val bytes = contents.toByteArray(Charsets.UTF_8)
            checkNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(bytes) }
            LibraryRepository.remember(context, uri, "application/octet-stream", "original.bin", bytes.size.toLong())
            return LibraryRepository.files.value.single { it.uri == uri.toString() }
        }

        fun seedHidden(uri: String) {
            check(context.getSharedPreferences("library", Context.MODE_PRIVATE).edit()
                .putStringSet("hidden_history", setOf(uri)).commit())
        }

        suspend fun assertHiddenRename(original: SavedFile) {
            LibraryRepository.rename(context, original, "renamed.bin")
            val renamed = LibraryRepository.files.value.single()
            assertNotEquals(original.uri, renamed.uri)
            assertEquals(setOf(renamed.uri), LibraryRepository.hidden(context))
            LibraryRepository.refresh(context)
            assertEquals(setOf(renamed.uri), LibraryRepository.hidden(context))
            assertTrue(LibraryRepository.files.value.any { it.uri == renamed.uri })
            assertContents(renamed)
        }

        fun assertContents(item: SavedFile) {
            checkNotNull(context.contentResolver.openInputStream(Uri.parse(item.uri))).use {
                assertEquals(contents, it.bufferedReader(Charsets.UTF_8).readText())
            }
        }
    }

    /** Keep edit operations staged, then reject the migration's commit. */
    private class FailingEditor(private val delegate: SharedPreferences.Editor) : SharedPreferences.Editor by delegate {
        override fun remove(key: String?): SharedPreferences.Editor {
            delegate.remove(key)
            return this
        }

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            delegate.putString(key, value)
            return this
        }

        override fun commit(): Boolean = false
    }

    private class HistoryReadGate(private val delegate: SharedPreferences) : SharedPreferences by delegate {
        val firstRead = CountDownLatch(1)
        val secondRead = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val first = AtomicBoolean(false)
        @Volatile var armed = false

        fun arm() {
            first.set(true)
            armed = true
        }

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            val snapshot = delegate.getStringSet(key, defValues)?.toMutableSet()
            if (armed && key == "hidden_history") {
                if (first.compareAndSet(true, false)) {
                    firstRead.countDown()
                    check(release.await(5, TimeUnit.SECONDS)) { "History read gate timed out" }
                } else secondRead.countDown()
            }
            return snapshot
        }
    }
}
