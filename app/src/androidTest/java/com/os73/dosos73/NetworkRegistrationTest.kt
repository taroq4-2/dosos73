package com.os73.dosos73

import android.net.ConnectivityManager
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

class NetworkRegistrationTest {
    @Test fun repeatedSubscriptionsReleaseSystemCallbacks() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(ConnectivityManager::class.java)
        // More than Android's per-UID callback limit: any leaked registration fails
        // this test without starting transfers or changing the user's network policy.
        withTimeout(30_000) {
            repeat(105) { NetworkSettings.changes(context, manager.activeNetwork).first() }
        }
    }
}
