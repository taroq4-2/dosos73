package com.os73.dosos73

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import android.os.Looper
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.UUID
import java.io.RandomAccessFile
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.EmptyCoroutineContext

/** One child process group, bounded diagnostics and no shell or permanent monitoring thread. */
internal class NativeProcessSession(
    private val context: Context,
    private val entry: File = File(context.noBackupFilesDir, "youtubedl-android/yt-dlp/yt-dlp"),
) : Closeable {
    val id = UUID.randomUUID().toString()
    private val groupFile = File(context.cacheDir, "native-session-$id.pid")
    private val groupGateFile = File(context.noBackupFilesDir, "native-process-groups.lock")
    // Authenticates the bootstrap identity even if a cache record is overwritten.
    private val groupSecret = UUID.randomUUID().toString() + UUID.randomUUID().toString()
    private val stopping = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val lease = ProcessLease(::terminate)
    @Volatile private var proxy: PublicProxy? = null

    fun attachProxy(value: PublicProxy) {
        proxy = value
        try { lease.checkActive() } catch (error: IOException) { value.close(); throw error }
    }

    fun cancel() {
        lease.cancel()
        runCatching { proxy?.close() }
    }

    private fun terminate(process: Process) {
        if (stopped.get() || !stopping.compareAndSet(false, true)) return
        val cleanup = Runnable {
            var interrupted = Thread.interrupted()
            var verified = false
            try {
                val deadline = System.nanoTime() + 2_000_000_000L
                while (true) {
                    val result = runCatching { withGroupGate { stopGroup(process) } }.getOrNull()
                    if (result != null) { verified = result; break }
                    // Stopping our own Process is safe even if the numeric group cannot
                    // yet be checked. It also releases a gate held by this bootstrap.
                    if (process.isAlive) runCatching { process.destroyForcibly() }
                    if (System.nanoTime() >= deadline) break
                    try { Thread.sleep(10) } catch (_: InterruptedException) { interrupted = true }
                }
            } finally {
                // The Process object is still safe to stop if group ownership could not be proved.
                // Never signal a stale numeric PGID merely to make cancellation appear successful.
                if (process.isAlive) runCatching { process.destroyForcibly() }
                if (verified) {
                    stopped.set(true)
                    groupFile.delete()
                } else {
                    Diagnostics.event(Stage.TRANSFER, "native_cleanup_unverified")
                }
                stopping.set(false)
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
        // No file-lock waiting or /proc scan on the UI thread, and no dedicated watchdog thread.
        if (Looper.myLooper() == Looper.getMainLooper())
            Dispatchers.IO.dispatch(EmptyCoroutineContext, cleanup)
        else cleanup.run()
    }

    private fun withGroupGate(action: () -> Boolean): Boolean? {
        if (!GROUP_GATE.tryLock()) return null
        try {
            RandomAccessFile(groupGateFile, "rw").use { gate ->
                val lock = try { gate.channel.tryLock() }
                    catch (_: OverlappingFileLockException) { null }
                if (lock == null) return null
                return lock.use { action() }
            }
        } finally { GROUP_GATE.unlock() }
    }

    private data class GroupIdentity(val pid: Int, val startTicks: Long)
    private data class ProcIdentity(val pid: Int, val group: Int, val session: Int, val startTicks: Long)

    /** All groups created by this class take the same gate in the Python bootstrap.
     * While held, a new application session cannot reuse an expired group's number.
     * This protocol does not authorize groups created by unrelated/future native code.
     */
    private fun stopGroup(process: Process): Boolean {
        val identity = runCatching {
            val fields = groupFile.readLimited(256).toString(Charsets.US_ASCII).trim().split(' ')
            require(fields.size == 4 && fields[2] == id)
            val payload = fields.take(3).joinToString(" ")
            val mac = Mac.getInstance("HmacSHA256").apply {
                init(SecretKeySpec(groupSecret.toByteArray(Charsets.US_ASCII), "HmacSHA256"))
            }.doFinal(payload.toByteArray(Charsets.US_ASCII)).joinToString("") { "%02x".format(it) }
            require(MessageDigest.isEqual(mac.toByteArray(Charsets.US_ASCII), fields[3].toByteArray(Charsets.US_ASCII)))
            GroupIdentity(fields[0].toInt(), fields[1].toLong()).also {
                require(it.pid > 1 && it.startTicks > 0 && it.pid != (procIdentity(android.os.Process.myPid())?.group ?: error("Own process group unavailable")))
            }
        }.getOrNull()
        var verified = false
        if (identity != null) {
            val leader = procIdentity(identity.pid)
            // A live or unreaped original leader is proved by its authenticated identity
            // and kernel start time; no /proc/environ permission is needed in this path.
            val ownsLeader = leader != null && leader.startTicks == identity.startTicks && memberOf(leader, identity)
            val owned = ownsLeader || hasOwnedMember(identity)
            verified = if (owned) {
                try { Os.kill(-identity.pid, OsConstants.SIGKILL); true }
                catch (error: ErrnoException) { error.errno == OsConstants.ESRCH }
            } else {
                // A genuinely absent group is already clean. EPERM or missing evidence is not.
                try { Os.kill(-identity.pid, 0); false }
                catch (error: ErrnoException) { error.errno == OsConstants.ESRCH }
            }
        }
        // Keep this inside the creation gate as well: a bootstrap that has not yet
        // published its identity cannot race past the gate and spawn helpers here.
        if (process.isAlive) runCatching { process.destroyForcibly() }
        return verified
    }

    private fun memberOf(member: ProcIdentity, identity: GroupIdentity): Boolean =
        member.group == identity.pid && member.session == identity.pid && member.startTicks >= identity.startTicks

    private fun hasOwnedMember(identity: GroupIdentity): Boolean {
        val deadline = System.nanoTime() + 200_000_000L
        return runCatching {
            Files.newDirectoryStream(File("/proc").toPath()).use { entries ->
                var examined = 0
                for (entry in entries) {
                    if (++examined > 2048 || System.nanoTime() >= deadline) return@use false
                    val pid = entry.fileName.toString().toIntOrNull() ?: continue
                    val member = procIdentity(pid) ?: continue
                    if (memberOf(member, identity) && hasSessionToken(pid)) return@use true
                }
                false
            }
        }.getOrDefault(false)
    }

    private fun procIdentity(pid: Int): ProcIdentity? = runCatching {
        val directory = File("/proc/$pid")
        // /proc/self can be owned by root when a Release process is non-dumpable.
        // Our live PID is already known; other processes still require UID ownership.
        require(pid == android.os.Process.myPid() ||
            Os.stat(directory.absolutePath).st_uid == android.os.Process.myUid())
        val stat = File(directory, "stat").readLimited(4096).toString(Charsets.US_ASCII)
        // comm can contain spaces and ')'; fields after its final ')' start at state.
        require(stat.substringBefore(' ').toInt() == pid)
        val fields = stat.substringAfterLast(") ").trim().split(Regex(" +"))
        require(fields.size > 19)
        ProcIdentity(pid, fields[2].toInt(), fields[3].toInt(), fields[19].toLong())
    }.getOrNull()

    private fun hasSessionToken(pid: Int): Boolean = runCatching {
        File("/proc/$pid/environ").readLimited(65536).toString(Charsets.UTF_8)
            .split('\u0000').any { it == "$SESSION_ENV=$id" }
    }.getOrDefault(false)

    private fun File.readLimited(limit: Int): ByteArray = inputStream().use { input ->
        val buffer = ByteArray(limit)
        var used = 0
        while (used < limit) {
            val count = input.read(buffer, used, limit - used)
            if (count < 0) break
            used += count
        }
        require(used < limit || input.read() < 0)
        buffer.copyOf(used)
    }

    fun execute(arguments: List<String>, progress: ((Float?, Boolean) -> Unit)? = null) {
        lease.checkActive()
        val native = context.applicationInfo.nativeLibraryDir
        val base = File(context.noBackupFilesDir, "youtubedl-android")
        val python = File(base, "packages/python/usr")
        val command = listOf("$native/libpython.so", "-u", "-c", BOOTSTRAP,
            groupFile.absolutePath, groupGateFile.absolutePath, entry.absolutePath) + arguments
        val process = ProcessBuilder(command).redirectErrorStream(true).apply {
            environment().apply {
                // The explicit authenticated proxy and bundled trust store own this session.
                keys.filter { it.lowercase() in setOf("http_proxy", "https_proxy", "all_proxy", "no_proxy",
                    "requests_ca_bundle", "curl_ca_bundle", "ssl_cert_dir", "pythonpath", "pythonstartup") }
                    .toList().forEach { remove(it) }
                put("LD_LIBRARY_PATH", "$native:$python/lib:$base/packages/ffmpeg/usr/lib")
                put("SSL_CERT_FILE", "$python/etc/tls/cert.pem")
                put("PYTHONHOME", python.absolutePath)
                put("HOME", python.absolutePath)
                put("PATH", System.getenv("PATH").orEmpty() + ":$native")
                put("TMPDIR", context.cacheDir.absolutePath)
                put(SESSION_ENV, id)
                put(SESSION_KEY_ENV, groupSecret)
            }
        }.start()
        val tail = StringBuilder()
        val line = StringBuilder()
        var processing = false
        var lastProgress = 0L
        val buffer = ByteArray(4096)
        fun accept(text: String) {
            tail.append(text)
            if (tail.length > 16384) tail.delete(0, tail.length - 16384)
            for (character in text) {
                if (character == '\n' || character == '\r') {
                    val current = line.toString()
                    line.setLength(0)
                    val wasProcessing = processing
                    if (current.contains("[Merger]") || current.contains("[ExtractAudio]")) processing = true
                    val percent = PERCENT.find(current)?.groupValues?.get(1)?.toFloatOrNull()
                    val now = System.nanoTime()
                    if (progress != null && (processing != wasProcessing ||
                        (percent != null && now - lastProgress >= 250_000_000L))) {
                        lastProgress = now
                        progress(percent?.takeIf { it in 0f..100f }?.div(100f), processing)
                    }
                } else if (line.length < 8192) line.append(character)
            }
        }
        try {
            lease.attach(process)
            while (true) {
                lease.checkActive()
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val count = process.inputStream.read(buffer)
                if (count < 0) break
                if (count > 0) accept(String(buffer, 0, count, Charsets.UTF_8))
            }
            lease.checkActive()
            if (process.waitFor() != 0) throw IOException(tail.toString().takeLast(16384))
        } finally {
            terminate(process)
            lease.detach(process)
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
        }
    }

    override fun close() { cancel() }

    companion object {
        private val GROUP_GATE = ReentrantLock()
        private const val SESSION_ENV = "DOSOS73_NATIVE_SESSION"
        private const val SESSION_KEY_ENV = "DOSOS73_NATIVE_SESSION_KEY"
        private val PERCENT = Regex("\\[download]\\s+([0-9]+(?:\\.[0-9]+)?)%")
        private val BOOTSTRAP = """
            import fcntl, hashlib, hmac, os, runpy, sys
            identity_key = os.environ.pop('DOSOS73_NATIVE_SESSION_KEY')
            with open(sys.argv[2], 'a+b') as gate:
                fcntl.lockf(gate, fcntl.LOCK_EX)
                try:
                    try:
                        os.setsid()
                    except PermissionError:
                        if os.getpgrp() != os.getpid(): raise
                    if os.getpgrp() != os.getpid(): raise RuntimeError('Process group unavailable')
                    with open('/proc/self/stat') as status:
                        ticks = status.read().rsplit(') ', 1)[1].split()[19]
                    identity = '{} {} {}'.format(os.getpid(), ticks, os.environ['DOSOS73_NATIVE_SESSION'])
                    tag = hmac.new(identity_key.encode('ascii'), identity.encode('ascii'), hashlib.sha256).hexdigest()
                    fd = os.open(sys.argv[1], os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                    try:
                        os.write(fd, (identity + ' ' + tag).encode('ascii'))
                    finally:
                        os.close(fd)
                finally:
                    fcntl.lockf(gate, fcntl.LOCK_UN)
            sys.argv = sys.argv[3:]
            runpy.run_path(sys.argv[0], run_name='__main__')
        """.trimIndent()
    }
}
