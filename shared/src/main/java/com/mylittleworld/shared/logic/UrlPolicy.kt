package com.mylittleworld.shared.logic

import java.net.URI

/**
 * Decides whether the child WebView may navigate to a URL.
 *
 * This is deliberately pure Kotlin (no Android types) so it can be unit tested
 * on the JVM. The WebView layer must call [isAllowed] for *every* navigation
 * attempt including subresource redirects triggered by the page, javascript
 * URIs, intent URIs and app-link handoffs.
 */
object UrlPolicy {

    private val BLOCKED_SCHEMES = setOf(
        "javascript", "file", "content", "data", "intent", "market", "chrome",
        "about", "blob", "filesystem", "ws", "wss", "tel", "sms", "mailto", "geo",
    )

    private val LOCAL_DEV_HOSTS = setOf("10.0.2.2", "localhost", "127.0.0.1")

    /** Hosts that are always rejected regardless of the allow list. */
    private val DENIED_HOST_SUFFIXES = listOf(
        "bit.ly", "tinyurl.com", "goo.gl", "t.co",
    )

    /**
     * Returns a normalised HTTPS URL suitable for storage, or null when the
     * parent-supplied value cannot be trusted.
     */
    fun sanitiseApprovedUrl(raw: String, allowCleartextLocalDev: Boolean = false): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > 2048) return null
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host.isBlank() || uri.userInfo != null || uri.fragment != null) return null
        val localHost = LOCAL_DEV_HOSTS.contains(host)
        if (scheme != "https" && !(scheme == "http" && allowCleartextLocalDev && localHost)) return null
        if (host.contains(' ') || !host.contains('.')) {
            if (!localHost) return null
        }
        if (isDeniedHost(host)) return null
        // Rebuild so the stored value is canonical (lower-case scheme + host).
        return runCatching {
            URI(scheme, null, host, uri.port, uri.path, uri.query, null).toString()
        }.getOrNull()
    }

    fun hostOf(url: String): String? = runCatching { URI(url).host?.lowercase() }.getOrNull()

    /**
     * @param url candidate navigation
     * @param approvedHosts hosts derived from the parent-approved entry URL
     * @param allowSubdomains allow `learn.example.com` when `example.com` is approved
     */
    fun isAllowed(
        url: String,
        approvedHosts: Collection<String>,
        allowSubdomains: Boolean = true,
        allowCleartextLocalDev: Boolean = false,
    ): Boolean {
        if (url.isBlank()) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme in BLOCKED_SCHEMES) return false
        if (scheme != "https" && scheme != "http") return false
        val host = uri.host?.lowercase() ?: return false
        if (host.isBlank()) return false
        if (scheme == "http" && !(allowCleartextLocalDev && LOCAL_DEV_HOSTS.contains(host))) return false
        if (isDeniedHost(host)) return false
        val normalised = approvedHosts.map { it.trim().lowercase() }.filter { it.isNotBlank() }
        if (normalised.isEmpty()) return false
        return normalised.any { approved ->
            host == approved || (allowSubdomains && host.endsWith(".$approved"))
        }
    }

    fun isDeniedHost(host: String): Boolean {
        val h = host.lowercase()
        return DENIED_HOST_SUFFIXES.any { h == it || h.endsWith(".$it") }
    }

    /** Builds the allow list from an approved entry URL. */
    fun approvedHostsFor(approvedUrl: String?): List<String> {
        val host = approvedUrl?.let { hostOf(it) } ?: return emptyList()
        return listOf(host)
    }
}
