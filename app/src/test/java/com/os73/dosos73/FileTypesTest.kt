package com.os73.dosos73

import org.junit.Assert.*
import org.junit.Test

class FileTypesTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private val zip = b(0x50, 0x4B, 0x03, 0x04)

    @Test
    fun androidPackageMimeTypesMapToApk() {
        for (mime in listOf("application/vnd.android.package-archive", "application/x-android-package")) {
            assertEquals(mime, "apk", FileTypes.extensionForMime(mime))
        }
        assertEquals(FileCategory.APP, FileTypes.category("apk"))
        assertEquals("application/vnd.android.package-archive", FileTypes.publishMime("apk"))
        // Containers Android cannot install directly are stored as opaque files.
        assertEquals("application/octet-stream", FileTypes.publishMime("xapk"))
    }

    @Test
    fun opaqueMimeTypesNeverDecideTheExtension() {
        for (mime in FileTypes.opaqueMimes) assertNull(mime, FileTypes.extensionForMime(mime))
    }

    @Test
    fun signaturesAreRecognised() {
        assertEquals("zip", FileTypes.sniff(zip)?.extension)
        assertEquals("rar", FileTypes.sniff("Rar!".toByteArray() + b(0x1A, 0x07, 0x00))?.extension)
        assertEquals("7z", FileTypes.sniff(b(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C))?.extension)
        assertEquals("pdf", FileTypes.sniff("%PDF-1.7".toByteArray())?.extension)
        assertEquals("png", FileTypes.sniff(b(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))?.extension)
        assertEquals("jpg", FileTypes.sniff(b(0xFF, 0xD8, 0xFF, 0xE0))?.extension)
        assertEquals("mp4", FileTypes.sniff(b(0, 0, 0, 0x18) + "ftypisom".toByteArray())?.extension)
        assertEquals("exe", FileTypes.sniff("MZ".toByteArray() + b(0x90, 0))?.extension)
        assertNull(FileTypes.sniff(ByteArray(0)))
        assertNull(FileTypes.sniff("plain words".toByteArray()))
    }

    @Test
    fun htmlIsDetectedThroughBomWhitespaceAndCase() {
        for (text in listOf("<!DOCTYPE html>", "﻿\n  <HTML lang=ar>", "\t<head>", "<!-- x -->", "<script>")) {
            assertTrue(text, FileTypes.looksLikeHtml(text.toByteArray()))
        }
        assertFalse(FileTypes.looksLikeHtml("<?xml version=\"1.0\"?><feed/>".toByteArray()))
        assertFalse(FileTypes.looksLikeHtml(zip))
    }

    @Test
    fun verifyComparesTheBytesWithTheClaimedType() {
        assertEquals(FileTypes.Verdict.Match, FileTypes.verify("apk", zip))
        assertEquals(FileTypes.Verdict.Match, FileTypes.verify("APK", zip))
        assertEquals(FileTypes.Verdict.WebPage, FileTypes.verify("apk", "<html><body>login".toByteArray()))
        assertEquals(FileTypes.Verdict.Mismatch("pdf"), FileTypes.verify("apk", "%PDF-1.4".toByteArray()))
        assertEquals(FileTypes.Verdict.Mismatch(null), FileTypes.verify("zip", "garbage!".toByteArray()))
        // No signature, or nothing to read yet: decided after the transfer.
        assertEquals(FileTypes.Verdict.Unverifiable, FileTypes.verify("txt", "hello".toByteArray()))
        assertEquals(FileTypes.Verdict.Unverifiable, FileTypes.verify("dmg", b(1, 2, 3)))
        assertEquals(FileTypes.Verdict.Unverifiable, FileTypes.verify("apk", ByteArray(0)))
        // Even a type without a signature can never be an HTML page.
        assertEquals(FileTypes.Verdict.WebPage, FileTypes.verify("iso", "<!doctype html>".toByteArray()))
    }

    @Test
    fun binaryTypesRejectTextEnvelopesButTextTypesDoNot() {
        for (extension in listOf("apk", "xapk", "zip", "rar", "7z", "pdf", "exe", "msi", "dmg", "iso", "mp4", "mp3", "png"))
            assertTrue(extension, FileTypes.isBinary(extension))
        for (extension in listOf("json", "xml", "txt", "csv", "unknown")) assertFalse(extension, FileTypes.isBinary(extension))
    }
}
