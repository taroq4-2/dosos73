package com.os73.dosos73

import android.net.ConnectivityManager
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

class NetworkRegistrationTest {
    private suspend fun stableBaseline(): Set<String> = withTimeout(5000) {
        var previous = ownedSystemRequests()
        var stable = 1
        while (stable < 3) {
            delay(25)
            val current = ownedSystemRequests()
            stable = if (current == previous) stable + 1 else 1
            previous = current
        }
        previous
    }

    private fun ownedSystemRequests(): Set<String> {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("dumpsys connectivity requests")
        val dump = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(count <= 1024 * 1024 - output.size()) { "Connectivity request dump exceeds test limit" }
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        }
        check(dump.isNotBlank() && dump.lineSequence().any { it.trimStart().startsWith("uid/pid:") }) {
            "Connectivity request dump format is unavailable"
        }
        val prefix = "uid/pid:${Process.myUid()}/"
        val identity = Regex("\\bid=(\\d+)\\b")
        return dump.lineSequence().map { it.trim() }.filter { it.startsWith(prefix) }.map { line ->
            checkNotNull(identity.find(line)?.groupValues?.get(1)) { "Owned connectivity request has no identity" }
        }.toSet()
    }

    @Test fun repeatedSubscriptionsReleaseSystemCallbacks() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(ConnectivityManager::class.java)
        // Android10 ConnectivityService.releaseNetworkRequest posts to its handler;
        // unregister returning does not mean the server-side UID quota is released.
        // Keep all105 subscriptions. After each bounded batch, inspect the actual
        // service-owned request IDs instead of assuming synchronous quota release.
        // A leaked callback fails the first drain assertion; no registration failure
        // is caught, skipped or retried, and network policy is never changed.
        val baseline = stableBaseline()
        var completed = 0
        withTimeout(60_000) {
            repeat(7) { batch ->
                val before = ownedSystemRequests().size
                repeat(15) {
                    NetworkSettings.changes(context, manager.activeNetwork).first()
                    completed++
                }
                withTimeout(5000) {
                    var stable = 0
                    while (stable < 3) {
                        stable = if ((ownedSystemRequests() - baseline).isEmpty()) stable + 1 else 0
                        if (stable < 3) delay(25)
                    }
                }
                println("Network callback batch=${batch + 1}, subscriptions=15, before=$before, after=${ownedSystemRequests().size}")
            }
        }
        assertEquals(105, completed)
        val retained = ownedSystemRequests() - baseline
        assertTrue("System retained newly owned callbacks after cancellation", retained.isEmpty())
        println("Network callback cleanup: completed=$completed, batches=7, retained=${retained.size}")
    }
}
