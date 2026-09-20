package ai.rever.boss.plugin.dynamic.bookmarks

import java.security.MessageDigest

/**
 * Canonicalises a URL before it becomes the key of a [Bookmark].
 *
 * Two URLs that differ only in tracking parameters, default port or case in
 * the host all hash to the same `id`, so revisiting the same article with
 * `?utm_source=foo` and without finds one record instead of two. The
 * canonical form is also the value stored in [Bookmark.url], so a panel
 * listing "all bookmarks for this page" sees the cleanest available URL.
 *
 * The rules, in order:
 *  - lowercase the scheme and host
 *  - drop the default port (80 for http, 443 for https)
 *  - sort the query parameters by name
 *  - drop tracking parameters: `utm_*`, `fbclid`, `gclid`
 *  - drop the fragment - it is page-state, not page-identity
 *
 * Non-http(s) schemes are returned unchanged so a `mailto:` or `file:` URL
 * can still get a record (the user might want to bookmark it). Path and
 * percent-encoding are left alone - rewriting them would risk encoding bugs
 * and is not needed to deduplicate the same article visited twice.
 *
 * The logic mirrors the page-memory plugin's [UrlCanonicalizer] so the two
 * plugins agree on what "the same URL" means - a bookmark and a page-memory
 * record keyed on the same URL always collide.
 */
object UrlCanonicalizer {

    private val DEFAULT_PORTS = mapOf(
        "http" to "80",
        "https" to "443",
    )

    /** Tracking parameters that should be dropped before hashing. */
    private val TRACKING_PREFIXES = listOf("utm_")
    private val TRACKING_EXACT = setOf("fbclid", "gclid")

    /**
     * Canonicalise [url].
     *
     * Returns [url] unchanged when it cannot be parsed as an absolute http(s)
     * URI - the caller can still store the original under a hash of its bytes,
     * which is what a `mailto:` or a malformed paste would do. Throws are
     * not used; a malformed URL is just returned as-is.
     */
    fun canonicalize(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return trimmed

        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0) return trimmed

        val scheme = trimmed.substring(0, schemeEnd).lowercase()
        val rest = trimmed.substring(schemeEnd + 3)
        val fragmentStart = rest.indexOf('#')
        val withoutFragment = if (fragmentStart >= 0) rest.substring(0, fragmentStart) else rest

        val queryStart = withoutFragment.indexOf('?')
        val pathPart = if (queryStart >= 0) withoutFragment.substring(0, queryStart) else withoutFragment
        val queryPart = if (queryStart >= 0) withoutFragment.substring(queryStart + 1) else null

        val (hostAndPort, path) = splitHostAndPath(pathPart)
        if (hostAndPort.isEmpty()) return trimmed

        val (host, port) = splitHostPort(hostAndPort, scheme)
        val canonicalHost = host.lowercase()

        val canonicalPort = port?.takeIf { it != DEFAULT_PORTS[scheme] }

        val canonicalQuery = queryPart?.takeIf { it.isNotEmpty() }
            ?.let { canonicalQuery(it) }
            ?.takeIf { it.isNotEmpty() }

        val authority = if (canonicalPort == null) canonicalHost else "$canonicalHost:$canonicalPort"
        val pathStr = path.ifEmpty { "/" }
        return buildString {
            append(scheme)
            append("://")
            append(authority)
            append(pathStr)
            if (canonicalQuery != null) {
                append('?')
                append(canonicalQuery)
            }
        }
    }

    /**
     * SHA-256 hex digest of [canonicalize]. The primary key of a [Bookmark]
     * is this hex string - 64 chars, lowercase, no prefix.
     */
    fun hash(url: String): String {
        val canonical = canonicalize(url)
        val bytes = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return bytes.joinToString(separator = "") { "%02x".format(it) }
    }

    private fun splitHostAndPath(s: String): Pair<String, String> {
        val slash = s.indexOf('/')
        return if (slash < 0) {
            s to ""
        } else {
            s.substring(0, slash) to s.substring(slash)
        }
    }

    private fun splitHostPort(hostAndPort: String, scheme: String): Pair<String, String?> {
        val colon = hostAndPort.lastIndexOf(':')
        return if (colon < 0) {
            hostAndPort to null
        } else {
            val port = hostAndPort.substring(colon + 1)
            val host = hostAndPort.substring(0, colon)
            // A port that is not all digits is probably an IPv6 literal or
            // a malformed URI; treat it as part of the host so we do not
            // return something nonsensical.
            if (port.all { it.isDigit() }) host to port else hostAndPort to null
        }
    }

    private fun canonicalQuery(query: String): String {
        val pairs = query.split('&').filter { it.isNotEmpty() }
        val filtered = pairs.filterNot { isTracking(it) }
        return filtered.sortedBy { it.substringBefore('=') }.joinToString("&")
    }

    private fun isTracking(pair: String): Boolean {
        val name = pair.substringBefore('=', missingDelimiterValue = pair)
        return name in TRACKING_EXACT || TRACKING_PREFIXES.any { name.startsWith(it) }
    }
}
