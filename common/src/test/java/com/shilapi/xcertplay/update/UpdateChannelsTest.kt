package com.shilapi.xcertplay.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateChannelsTest {
    @Test fun githubIsTheOnlyDefaultSource() {
        val sources = UpdateChannels.sources(0)
        assertEquals(listOf(UpdateClient.GITHUB_RELEASES_URL), sources.map { it.releasesUrl })
    }

    @Test fun theRetiredGiteeChoiceFallsBackToGithub() {
        assertEquals(UpdateChannels.sources(0), UpdateChannels.sources(1))
    }

    @Test fun mirrorChoiceAppliesToBothMetadataAndAssets() {
        val mirror = UpdateChannels.sources(2).first()
        assertTrue(mirror.releasesUrl.startsWith("https://gh-proxy.com/https://api.github.com/"))
        assertEquals("https://gh-proxy.com/https://example.com/DiPlay-cn.apk",
            mirror.assetUrl("https://example.com/DiPlay-cn.apk"))
        assertEquals(UpdateClient.GITHUB_RELEASES_URL, UpdateChannels.sources(2).last().releasesUrl)
    }
}
