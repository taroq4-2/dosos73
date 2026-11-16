package com.os73.dosos73

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeResumeAdapterTest {
    @Test fun bundledPythonAndYtdlpResumeTheExactCheckpointWithoutNetwork() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.cacheDir, "resume-adapter-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            EngineRuntime.initialize(context)
            context.assets.open("verified_http.py").use { input -> File(folder, "verified_http.py").outputStream().use(input::copyTo) }
            val script = File(folder, "check.py").apply { writeText("""
                import io, sys
                from pathlib import Path
                sys.path.insert(0, str(Path(__file__).parent))
                sys.path.insert(0, sys.argv[1])
                from verified_http import install
                install()
                from yt_dlp import YoutubeDL
                from yt_dlp.downloader.http import HttpFD
                from yt_dlp.utils.networking import HTTPHeaderDict
                body = bytes(range(256)) * 1024
                calls = []
                class Response(io.BytesIO):
                    def __init__(self, resumed):
                        super().__init__(body[65536:] if resumed else body)
                        self.status = 206 if resumed else 200
                        self.url = 'https://fixture.example/video'
                        self.headers = HTTPHeaderDict({'Content-Type':'video/mp4', 'ETag':'"v1"',
                            'Content-Length': str(len(body)-65536 if resumed else len(body))})
                        if resumed: self.headers['Content-Range'] = 'bytes 65536-262143/262144'
                        self.resumed = resumed
                    def read(self, n=-1):
                        if not self.resumed and self.tell() >= 65536: raise OSError('fixture interruption')
                        return super().read(n)
                def response(request):
                    calls.append(dict(request.headers))
                    return Response(len(calls)>1)
                destination = str(Path(__file__).with_name('media.mp4'))
                with YoutubeDL({'quiet':True, 'no_warnings':True, 'noprogress':True}) as ydl:
                    ydl.urlopen = response
                    downloader = HttpFD(ydl, {'quiet':True, 'noprogress':True, 'continuedl':True})
                    info = {'url':'https://fixture.example/video', 'protocol':'https', 'http_headers':{}}
                    try: downloader.download(destination, info)
                    except OSError: pass
                    else: raise AssertionError('Interrupted attempt unexpectedly succeeded')
                    assert not Path(destination).exists()
                    assert downloader.download(destination, info)[0]
                assert Path(destination).read_bytes() == body
                headers = {k.lower():v for k,v in calls[-1].items()}
                assert headers['range'] == 'bytes=65536-' and headers['if-range'] == '"v1"'
                Path(__file__).with_name('result').write_text('PASS')
            """.trimIndent()) }
            withContext(Dispatchers.IO) {
                NativeProcessSession(context, script).use { it.execute(listOf(
                    File(context.noBackupFilesDir, "youtubedl-android/yt-dlp/yt-dlp").absolutePath)) }
            }
            assertEquals("PASS", File(folder, "result").readText())
        } finally { folder.deleteRecursively() }
    }
}
