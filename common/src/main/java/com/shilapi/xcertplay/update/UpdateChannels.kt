package com.shilapi.xcertplay.update

/** CN release sources. The first entry is the driver's choice; the others are fallbacks. */
internal object UpdateChannels {
    data class Source(val releasesUrl: String, val assetPrefix: String = "") {
        fun assetUrl(url: String): String = assetPrefix + url
    }

    private val github = Source(UpdateClient.GITHUB_RELEASES_URL)
    private val mirrorOne = Source("https://gh-proxy.com/${UpdateClient.GITHUB_RELEASES_URL}", "https://gh-proxy.com/")
    private val mirrorTwo = Source("https://ghproxy.net/${UpdateClient.GITHUB_RELEASES_URL}", "https://ghproxy.net/")

    fun sources(choice: Int): List<Source> = when (choice) {
        2 -> listOf(mirrorOne, github)
        3 -> listOf(mirrorTwo, github)
        else -> listOf(github)
    }
}
