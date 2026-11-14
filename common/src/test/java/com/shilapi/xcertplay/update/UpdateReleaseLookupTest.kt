package com.shilapi.xcertplay.update

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class UpdateReleaseLookupTest {
    @Test fun theDefaultChannelAsksGithubAndKeepsItsApk() {
        val calls = mutableListOf<String>()
        val release = UpdateReleaseLookup.latest("0.2.17-cn.2", "DiPlay/test") { url ->
            calls.add(url.toString())
            FakeConnection(url, "[" + github("v0.2.17-cn.3") + "]")
        }

        assertEquals(listOf(UpdateClient.GITHUB_RELEASES_URL), calls)
        assertEquals("v0.2.17-cn.3", release?.tagName)
        assertEquals(
            "https://github.com/serein-morii/DiPlay-CN/releases/download/DiPlay-cn-v0.2.17-cn.3.apk",
            release?.apkUrl,
        )
    }

    @Test fun nothingIsOfferedWhenTheInstalledBuildIsCurrent() {
        val release = UpdateReleaseLookup.latest("0.2.17-cn.3", "DiPlay/test") { url ->
            FakeConnection(url, "[" + github("v0.2.17-cn.3") + "]")
        }
        assertNull(release)
    }

    private fun github(tag: String): String = """{
        "tag_name":"$tag",
        "assets":[
          {"name":"DiPlay-cn-$tag.apk","browser_download_url":"https://github.com/serein-morii/DiPlay-CN/releases/download/DiPlay-cn-$tag.apk"},
          {"name":"DiPlay-cn-$tag.apk.sha256","browser_download_url":"https://github.com/serein-morii/DiPlay-CN/releases/download/DiPlay-cn-$tag.apk.sha256"}
        ]
    }"""

    private class FakeConnection(url: URL, private val body: String) : HttpURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = 200
        override fun getInputStream(): InputStream = ByteArrayInputStream(body.toByteArray())
    }
}
