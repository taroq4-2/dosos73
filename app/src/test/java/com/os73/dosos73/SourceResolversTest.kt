package com.os73.dosos73

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SourceResolversTest {
    private fun github(tag: String? = null) = SourceResolvers.Target.GitHubRelease("taroq4-2", "dosos73", tag)

    @Test
    fun recognisesGitHubReleasePagesButNotFilesOrOtherPages() {
        assertEquals(github(), SourceResolvers.match("https://github.com/taroq4-2/dosos73"))
        assertEquals(github(), SourceResolvers.match("https://github.com/taroq4-2/dosos73/releases"))
        assertEquals(github(), SourceResolvers.match("https://github.com/taroq4-2/dosos73/releases/latest"))
        assertEquals(github("v1.3.2"), SourceResolvers.match("https://github.com/taroq4-2/dosos73/releases/tag/v1.3.2"))
        // An asset link is already a file; the probe follows its redirects.
        assertNull(SourceResolvers.match("https://github.com/taroq4-2/dosos73/releases/download/v1.3.2/dosos73.apk"))
        assertNull(SourceResolvers.match("https://github.com/taroq4-2/dosos73/blob/main/README.md"))
        assertNull(SourceResolvers.match("http://github.com/taroq4-2/dosos73"))
        assertNull(SourceResolvers.match("https://github.com/-bad/name"))
    }

    @Test
    fun recognisesFDroidPackagePagesWithOrWithoutLocale() {
        val target = SourceResolvers.Target.FDroidPackage("org.fdroid.fdroid")
        assertEquals(target, SourceResolvers.match("https://f-droid.org/packages/org.fdroid.fdroid/"))
        assertEquals(target, SourceResolvers.match("https://f-droid.org/ar/packages/org.fdroid.fdroid"))
        assertNull(SourceResolvers.match("https://f-droid.org/packages/not-a-package/"))
        assertNull(SourceResolvers.match("https://f-droid.org/en/docs/"))
    }

    @Test
    fun endpointsAreTheDocumentedPublicApis() {
        assertEquals("https://api.github.com/repos/taroq4-2/dosos73/releases/latest",
            SourceResolvers.endpoint(github()).toString())
        assertEquals("https://api.github.com/repos/taroq4-2/dosos73/releases/tags/v1.3.2",
            SourceResolvers.endpoint(github("v1.3.2")).toString())
        // Tags are path-encoded, never spliced into the URL.
        assertEquals("https://api.github.com/repos/taroq4-2/dosos73/releases/tags/a%2Fb",
            SourceResolvers.endpoint(github("a/b")).toString())
        assertEquals("https://f-droid.org/api/v1/packages/org.fdroid.fdroid",
            SourceResolvers.endpoint(SourceResolvers.Target.FDroidPackage("org.fdroid.fdroid")).toString())
    }

    private fun asset(name: String, url: String, size: Long = 10) =
        """{"name":"$name","size":$size,"browser_download_url":"$url"}"""

    private val base = "https://github.com/taroq4-2/dosos73/releases/download/v1.3.2"

    @Test
    fun githubAssetsKeepOnlyFilesOfThatReleaseAndRankApksFirst() {
        val json = """{"assets":[
            ${asset("SHA256SUMS.txt.sha256", "$base/SHA256SUMS.txt.sha256")},
            ${asset("source.zip", "$base/source.zip")},
            ${asset("app-x86.apk", "$base/app-x86.apk")},
            ${asset("app-universal.apk", "$base/app-universal.apk", 58422468)},
            ${asset("evil.apk", "https://evil.example/evil.apk")},
            ${asset("other.apk", "https://github.com/someone/else/releases/download/v1/other.apk")}
        ]}"""
        val choices = SourceResolvers.githubAssets(json, github("v1.3.2")) as SourceResolvers.Resolution.Choices
        assertEquals(listOf("app-universal.apk", "app-x86.apk", "source.zip", "SHA256SUMS.txt.sha256"),
            choices.candidates.map { it.name })
        assertEquals(58422468L, choices.candidates.first().sizeBytes)
    }

    @Test
    fun singleAssetIsDirectAndNoAssetIsASpecificFailure() {
        val one = SourceResolvers.githubAssets("""{"assets":[${asset("a.apk", "$base/a.apk")}]}""", github())
        assertEquals(SourceResolvers.Resolution.Direct("$base/a.apk"), one)
        val none = runCatching { SourceResolvers.githubAssets("""{"assets":[]}""", github()) }.exceptionOrNull()
        assertEquals(FailureCode.SOURCE_NO_FILE, (none as PipelineException).code)
    }

    @Test
    fun deviceAbiApkRanksAboveOtherAbis() {
        val ranked = SourceResolvers.rank(listOf(
            LinkCandidate("app-armeabi-v7a.apk", "u1", null),
            LinkCandidate("app-arm64-v8a.apk", "u2", null),
            LinkCandidate("notes.pdf", "u3", null),
        ), abis = listOf("arm64-v8a"))
        assertEquals(listOf("app-arm64-v8a.apk", "app-armeabi-v7a.apk", "notes.pdf"), ranked.map { it.name })
    }

    @Test
    fun fdroidUsesTheSuggestedVersionFile() = runBlocking {
        val target = SourceResolvers.Target.FDroidPackage("org.fdroid.fdroid")
        val resolution = SourceResolvers.resolve(target) { url, _ ->
            assertEquals("https://f-droid.org/api/v1/packages/org.fdroid.fdroid", url)
            """{"packageName":"org.fdroid.fdroid","suggestedVersionCode":1021050,"packages":[]}"""
        }
        assertEquals(SourceResolvers.Resolution.Direct("https://f-droid.org/repo/org.fdroid.fdroid_1021050.apk"), resolution)
    }
}
