package com.os73.dosos73

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** A file offered by a recognised source page (e.g. one asset of a GitHub release). */
data class LinkCandidate(val name: String, val url: String, val sizeBytes: Long?)

/**
 * Turns well-known download *pages* into their real files using the sources' own documented
 * public endpoints. Nothing here signs in, scrapes protected content or bypasses a gate:
 *  - GitHub: REST API `GET /repos/{owner}/{repo}/releases/latest` and `/releases/tags/{tag}`
 *    (https://docs.github.com/rest/releases/releases). Unauthenticated, rate-limited by GitHub.
 *  - F-Droid: `GET https://f-droid.org/api/v1/packages/{package}` and the repository file
 *    `https://f-droid.org/repo/{package}_{versionCode}.apk` (https://f-droid.org/docs/All_our_APIs/).
 * Direct asset links (`/releases/download/...`) need no resolver: the probe follows them.
 */
internal object SourceResolvers {
    sealed interface Resolution {
        data class Direct(val url: String) : Resolution
        data class Choices(val candidates: List<LinkCandidate>) : Resolution
    }

    sealed interface Target {
        /** [tag] null means the latest release. */
        data class GitHubRelease(val owner: String, val repo: String, val tag: String?) : Target
        data class FDroidPackage(val packageName: String) : Target
    }

    private val githubName = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})")
    private val githubRepo = Regex("[A-Za-z0-9._-]{1,100}")
    private val androidPackage = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")

    /** Pure URL classification; no network. */
    fun match(url: String): Target? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (!parsed.isHttps) return null
        val path = parsed.pathSegments.filter { it.isNotEmpty() }
        return when (parsed.host.lowercase()) {
            "github.com", "www.github.com" -> github(path)
            "f-droid.org", "www.f-droid.org" -> fdroid(path)
            else -> null
        }
    }

    private fun github(path: List<String>): Target? {
        if (path.size < 2 || !githubName.matches(path[0]) || !githubRepo.matches(path[1])) return null
        val owner = path[0]
        val repo = path[1].removeSuffix(".git")
        return when {
            path.size == 2 -> Target.GitHubRelease(owner, repo, null)
            path[2] != "releases" -> null
            path.size == 3 -> Target.GitHubRelease(owner, repo, null)
            path.size == 4 && path[3] == "latest" -> Target.GitHubRelease(owner, repo, null)
            path.size == 5 && path[3] == "tag" && path[4].length in 1..200 -> Target.GitHubRelease(owner, repo, path[4])
            else -> null // releases/download/... is already a file
        }
    }

    private fun fdroid(path: List<String>): Target? {
        // /packages/{id}/ or /{locale}/packages/{id}/
        val index = path.indexOf("packages")
        if (index !in 0..1 || path.size != index + 2) return null
        val id = path[index + 1]
        return if (id.length <= 255 && androidPackage.matches(id)) Target.FDroidPackage(id) else null
    }

    /** Endpoint queried for [target]; exposed for tests and auditing. */
    fun endpoint(target: Target): HttpUrl = when (target) {
        is Target.GitHubRelease -> HttpUrl.Builder().scheme("https").host("api.github.com")
            .addPathSegment("repos").addPathSegment(target.owner).addPathSegment(target.repo)
            .addPathSegment("releases")
            .apply { if (target.tag == null) addPathSegment("latest") else addPathSegment("tags").addPathSegment(target.tag) }
            .build()
        is Target.FDroidPackage -> HttpUrl.Builder().scheme("https").host("f-droid.org")
            .addPathSegments("api/v1/packages").addPathSegment(target.packageName).build()
    }

    suspend fun resolve(target: Target, fetchJson: suspend (url: String, accept: String) -> String): Resolution =
        when (target) {
            is Target.GitHubRelease -> githubAssets(
                fetchJson(endpoint(target).toString(), "application/vnd.github+json"), target)
            is Target.FDroidPackage -> fdroidFile(fetchJson(endpoint(target).toString(), "application/json"), target)
        }

    internal fun githubAssets(json: String, target: Target.GitHubRelease): Resolution {
        val assets = JSONObject(json).optJSONArray("assets")
            ?: throw PipelineException(Stage.EXTRACT, FailureCode.SOURCE_NO_FILE)
        val prefix = "/${target.owner}/${target.repo}/releases/download/".lowercase()
        val candidates = (0 until minOf(assets.length(), 200)).mapNotNull { index ->
            val asset = assets.optJSONObject(index) ?: return@mapNotNull null
            val link = asset.optString("browser_download_url").toHttpUrlOrNull() ?: return@mapNotNull null
            // Only files of this very release on github.com; never an arbitrary URL from a response.
            if (!link.isHttps || link.host != "github.com" || !link.encodedPath.lowercase().startsWith(prefix))
                return@mapNotNull null
            val name = asset.optString("name").filterNot { it.isISOControl() }.take(200).ifBlank { return@mapNotNull null }
            LinkCandidate(name, link.toString(), asset.optLong("size", -1).takeIf { it >= 0 })
        }
        return pick(rank(candidates))
    }

    internal fun fdroidFile(json: String, target: Target.FDroidPackage): Resolution {
        val document = JSONObject(json)
        val code = document.optLong("suggestedVersionCode", -1).takeIf { it > 0 }
            ?: throw PipelineException(Stage.EXTRACT, FailureCode.SOURCE_NO_FILE)
        return Resolution.Direct("https://f-droid.org/repo/${target.packageName}_$code.apk")
    }

    private val checksums = Regex("(?i)\\.(sha(1|256|512)(sum)?|md5|asc|sig|minisig|sbom|cdx\\.json|spdx\\.json)$")

    /**
     * Android packages first (universal or this device's ABI before other ABIs), then other files,
     * then checksums and signatures. Stable for equal ranks.
     */
    internal fun rank(candidates: List<LinkCandidate>, abis: List<String> = deviceAbis()): List<LinkCandidate> =
        candidates.sortedBy { candidate ->
            val name = candidate.name.lowercase()
            val extension = name.substringAfterLast('.', "")
            when {
                checksums.containsMatchIn(name) -> 9
                FileTypes.category(extension) == FileCategory.APP -> when {
                    "universal" in name -> 0
                    abis.any { abiToken(it).any(name::contains) } -> 1
                    knownAbiTokens.any(name::contains) -> 3
                    else -> 2
                }
                else -> 5
            }
        }

    private fun pick(ranked: List<LinkCandidate>): Resolution = when (ranked.size) {
        0 -> throw PipelineException(Stage.EXTRACT, FailureCode.SOURCE_NO_FILE)
        1 -> Resolution.Direct(ranked.single().url)
        else -> Resolution.Choices(ranked)
    }

    private val knownAbiTokens = listOf("arm64", "aarch64", "armeabi", "armv7", "x86_64", "x86", "x64")

    private fun abiToken(abi: String): List<String> = when (abi) {
        "arm64-v8a" -> listOf("arm64", "aarch64")
        "armeabi-v7a" -> listOf("armeabi", "armv7")
        "x86_64" -> listOf("x86_64", "x64")
        "x86" -> listOf("x86")
        else -> emptyList()
    }

    private fun deviceAbis(): List<String> =
        runCatching { android.os.Build.SUPPORTED_ABIS?.toList() }.getOrNull().orEmpty()
}
