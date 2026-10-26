package com.os73.dosos73

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import org.junit.Test

class TlsVersionTest {
    @Test
    fun compareVerifiedTlsVersions() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("tlsCompare") == "true")
        val c = InstrumentationRegistry.getInstrumentation().targetContext
        BundledEngine.install(c)
        com.yausername.youtubedl_android.YoutubeDL.init(c)
        val base = File(c.noBackupFilesDir, "youtubedl-android")
        val python = File(base, "packages/python/usr")
        val output =
            File(c.getExternalFilesDir(null), "evidence/tls-comparison.txt").apply {
                parentFile!!.mkdirs()
            }
        val code =
            """
            import ssl, urllib.request, sys, hashlib
            print(ssl.OPENSSL_VERSION, flush=True)
            for mode in ('default', 'tls12'):
                for attempt in range(3):
                    ctx=ssl.create_default_context()
                    if mode=='tls12': ctx.maximum_version=ssl.TLSVersion.TLSv1_2
                    opener=urllib.request.build_opener(urllib.request.ProxyHandler({} if sys.argv[1]=='direct' else {'https':sys.argv[1]}),urllib.request.HTTPSHandler(context=ctx))
                    try:
                        n=0; h=hashlib.sha256()
                        with opener.open('https://raw.githubusercontent.com/mediaelement/mediaelement-files/master/big_buck_bunny.mp4',timeout=15) as r:
                            while True:
                                chunk=r.read(65536)
                                if not chunk: break
                                n+=len(chunk); h.update(chunk)
                        print(mode,attempt,n,h.hexdigest(),flush=True)
                    except Exception as e: print(mode,attempt,type(e).__name__,flush=True)
        """
                .trimIndent()
        PublicProxy().use { p ->
            // Test-only direct comparison is restricted to the literal public fixture above.
            val route =
                if (InstrumentationRegistry.getArguments().getString("route") == "direct") "direct"
                else p.url
            val process =
                ProcessBuilder(
                        "${c.applicationInfo.nativeLibraryDir}/libpython.so",
                        "-c",
                        code,
                        route,
                    )
                    .redirectErrorStream(true)
                    .redirectOutput(output)
                    .apply {
                        environment()["LD_LIBRARY_PATH"] =
                            "$python/lib:$base/packages/ffmpeg/usr/lib"
                        environment()["PYTHONHOME"] = python.absolutePath
                        environment()["SSL_CERT_FILE"] = "$python/etc/tls/cert.pem"
                        if (
                            InstrumentationRegistry.getArguments().getString("softwareCrypto") ==
                                "true"
                        )
                            environment()["OPENSSL_ia32cap"] = "0"
                    }
                    .start()
            try {
                check(process.waitFor(150, TimeUnit.SECONDS))
                check(process.exitValue() == 0)
            } finally {
                process.destroy()
            }
        }
    }
}
