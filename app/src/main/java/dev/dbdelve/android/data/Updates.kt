package dev.dbdelve.android.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * New versions, read from the repository's GitHub Releases: the same page
 * the README sends people to, and the only place an APK is published. The
 * app finds a release and links to its APK; Android's own installer does the
 * installing, so the app needs no permission to install packages.
 */
object Updates {
    const val LATEST = "https://api.github.com/repos/Neoterux/dbdelve-android/releases/latest"

    /** A published release: its version without the `v`, its page, its APK. */
    data class Release(val version: String, val page: String, val apk: String?) {
        /** Where Download goes: the APK itself, or the page when it has none. */
        val download: String get() = apk ?: page
    }

    /**
     * The newest release that is neither a draft nor a pre-release (GitHub's
     * own `latest`), or null when the repository has published none.
     */
    fun fetchLatest(url: String = LATEST): Release? {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "DBDelve-Android")
            return when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> parse(connection.inputStream.bufferedReader().use { it.readText() })
                HttpURLConnection.HTTP_NOT_FOUND -> null
                // GitHub's limit for requests without a token is per address,
                // so a shared network can run it out.
                HttpURLConnection.HTTP_FORBIDDEN, 429 -> throw IOException("GitHub is limiting requests from this network; try again later")
                else -> throw IOException("GitHub answered $code")
            }
        } finally {
            connection.disconnect()
        }
    }

    /** One release as GitHub's API returns it; null when it names no version. */
    fun parse(json: String): Release? {
        val release = JSONObject(json)
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
        val version = release.optString("tag_name").removePrefix("v").trim()
        if (parts(version) == null) return null
        val assets = release.optJSONArray("assets")
        val apk = assets?.let { array ->
            (0 until array.length())
                .map { array.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                ?.optString("browser_download_url")
                ?.takeIf { it.startsWith("https://") }
        }
        val page = release.optString("html_url").takeIf { it.startsWith("https://") }
            ?: "https://github.com/Neoterux/dbdelve-android/releases/latest"
        return Release(version, page, apk)
    }

    /**
     * Whether [candidate] is a later version than [installed], both dotted
     * numbers with an optional `v` and `-suffix`. A pre-release of a version
     * (`0.3.0-dev`) is older than the version itself. Anything that is not a
     * version is never newer: a wrong "update available" is worse than a
     * missed one.
     */
    fun isNewer(candidate: String, installed: String): Boolean {
        val next = parts(candidate) ?: return false
        val have = parts(installed) ?: return false
        for (i in 0 until maxOf(next.first.size, have.first.size)) {
            val a = next.first.getOrElse(i) { 0 }
            val b = have.first.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return have.second && !next.second
    }

    /** The numbers of a version, and whether it carries a pre-release suffix. */
    private fun parts(version: String): Pair<List<Int>, Boolean>? {
        val bare = version.trim().removePrefix("v").substringBefore('+')
        val core = bare.substringBefore('-')
        if (core.isEmpty()) return null
        val numbers = core.split('.').map { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return null }
        return numbers to (bare.length != core.length)
    }
}
