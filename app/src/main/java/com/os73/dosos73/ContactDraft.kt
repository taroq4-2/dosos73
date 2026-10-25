package com.os73.dosos73

data class ContactDraft(val name: String, val email: String, val subject: String, val message: String) {
    val valid: Boolean get() = name.length <= 120 && email.length <= 254 &&
        Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+").matches(email.trim()) &&
        subject.isNotBlank() && subject.length <= 160 && subject.none { it == '\r' || it == '\n' } &&
        message.isNotBlank() && message.length <= 5000
    fun body() = "Name: ${name.trim()}\nEmail: ${email.trim()}\n\n${message.trim()}"
    fun copyText() = "To: contact@os73.com\nSubject: ${subject.trim()}\n\n${body()}"
}
