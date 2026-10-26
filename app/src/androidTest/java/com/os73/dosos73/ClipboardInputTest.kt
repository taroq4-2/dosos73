package com.os73.dosos73

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test

class ClipboardInputTest {
    @Test
    fun emptyOrImageClipboardDoesNotBecomeUrlError() {
        val model = MainViewModel(ApplicationProvider.getApplicationContext())
        Transfers.update(TransferState())
        for (text in listOf(null, "", "ordinary text", "content://media/images/1")) {
            model.paste(text)
            assertEquals(R.string.nothing_to_paste, model.state.value.error)
            assertFalse(model.state.value.busy)
        }
    }

    @Test
    fun multipleLinksRemainRejectedRatherThanSilentlyChoosingOne() {
        val model = MainViewModel(ApplicationProvider.getApplicationContext())
        Transfers.update(TransferState())
        model.paste("https://example.com/one https://example.com/two")
        assertEquals(R.string.error_url, model.state.value.error)
        assertFalse(model.state.value.busy)
    }
}
