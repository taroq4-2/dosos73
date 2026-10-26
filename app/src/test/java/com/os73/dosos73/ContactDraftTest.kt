package com.os73.dosos73

import org.junit.Assert.*
import org.junit.Test

class ContactDraftTest {
    @Test
    fun nameIsOptionalAndContentIsPreserved() {
        val draft = ContactDraft("", "person@example.com", "مساعدة", "أحتاج مساعدة في التنزيل")
        assertTrue(draft.valid)
        assertTrue(draft.copyText().contains("contact@os73.com"))
        assertTrue(draft.body().contains(draft.message))
    }

    @Test
    fun rejectsMissingFieldsAndHeaderInjection() {
        val valid = ContactDraft("", "person@example.com", "Support", "Message")
        assertFalse(valid.copy(email = "not an address").valid)
        assertFalse(valid.copy(subject = " ").valid)
        assertFalse(valid.copy(message = " ").valid)
        assertFalse(valid.copy(subject = "hello\r\nBcc: other@example.com").valid)
        assertFalse(valid.copy(message = "a".repeat(5001)).valid)
    }
}
