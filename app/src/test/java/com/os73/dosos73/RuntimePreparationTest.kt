package com.os73.dosos73

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimePreparationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun failedPreparationIsRetriedBeforeItIsRemembered() {
        val preparation = RuntimePreparation()
        val root = temporaryFolder.newFolder()
        val python = File(root, "python").apply { mkdirs() }
        val ffmpeg = File(root, "ffmpeg").apply { mkdirs() }
        File(python, "old").writeText("old Python")
        File(ffmpeg, "old").writeText("old FFmpeg")
        var attempts = 0
        val invalidate: () -> Unit = {
            attempts++
            check(!python.exists() || python.deleteRecursively())
            if (attempts == 1) throw IOException("FFmpeg removal failed")
            check(!ffmpeg.exists() || ffmpeg.deleteRecursively())
        }

        assertThrows(IOException::class.java) { preparation.prepare("apk-A", invalidate) }
        assertTrue(ffmpeg.exists())
        preparation.prepare("apk-A", invalidate)
        assertFalse(python.exists())
        assertFalse(ffmpeg.exists())
        preparation.prepare("apk-A", invalidate)
        assertEquals(2, attempts)
    }

    @Test fun eachApkIdentityGetsItsOwnSuccessfulPreparation() {
        val preparation = RuntimePreparation()
        val prepared = mutableListOf<String>()
        for (identity in listOf("apk-A", "apk-A", "apk-B", "apk-B", "apk-A")) {
            preparation.prepare(identity) { prepared.add(identity) }
        }
        assertEquals(listOf("apk-A", "apk-B"), prepared)
    }

    @Test fun laterStageFailuresDoNotDeleteAnInitializedSingletonsFilesOnRetry() {
        // All roots belong to this JVM test. No Android runtime or app data is used.
        for (failedStage in listOf("common", "ffmpeg", "marker")) {
            for (oldIdentity in listOf(null, "apk-old")) {
                val install = InstallAttempt(temporaryFolder.newFolder())
                if (oldIdentity != null) install.marker.writeText(oldIdentity)
                assertThrows(IOException::class.java) { install.initialize(failedStage) }
                assertEquals(oldIdentity, install.marker.takeIf { it.isFile }?.readText())
                assertEquals("native Python", install.nativePython.readText())

                install.initialize()
                assertEquals(1, install.invalidations)
                assertEquals(1, install.pythonExtractions)
                assertEquals(1, install.ffmpegExtractions)
                assertEquals("native Python", install.nativePython.readText())
                assertEquals("shared Python", install.common.readText())
                assertEquals("native FFmpeg", install.ffmpeg.readText())
                assertEquals("apk-A", install.marker.readText())
                assertEquals("user media", install.userFile.readText())
            }
        }
    }

    /** Models upstream init's early return after Python successfully initializes. */
    private class InstallAttempt(private val root: File) {
        private val preparation = RuntimePreparation()
        private val python = File(root, "packages/python")
        val nativePython = File(python, "native")
        val common = File(python, "common")
        val ffmpeg = File(root, "packages/ffmpeg/native")
        val marker = File(root, ".dosos-runtime-install")
        val userFile = File(root, "user-download").apply { writeText("user media") }
        var invalidations = 0
        var pythonExtractions = 0
        var ffmpegExtractions = 0
        private var pythonInitialized = false
        private var ffmpegInitialized = false

        fun initialize(failedStage: String? = null) {
            preparation.prepare("apk-A") {
                if (!marker.isFile || marker.readText() != "apk-A") {
                    check(!python.exists() || python.deleteRecursively())
                    check(!ffmpeg.parentFile!!.exists() || ffmpeg.parentFile!!.deleteRecursively())
                    invalidations++
                }
            }
            if (!pythonInitialized) {
                check(python.mkdirs() || python.isDirectory)
                nativePython.writeText("native Python")
                pythonExtractions++
                pythonInitialized = true
            }
            check(python.isDirectory)
            if (failedStage == "common") throw IOException("Shared extraction failed")
            common.writeText("shared Python")
            if (failedStage == "ffmpeg") throw IOException("FFmpeg extraction failed")
            if (!ffmpegInitialized) {
                check(ffmpeg.parentFile!!.mkdirs() || ffmpeg.parentFile!!.isDirectory)
                ffmpeg.writeText("native FFmpeg")
                ffmpegExtractions++
                ffmpegInitialized = true
            }
            if (failedStage == "marker") throw IOException("Completion marker failed")
            marker.writeText("apk-A")
        }
    }
}
