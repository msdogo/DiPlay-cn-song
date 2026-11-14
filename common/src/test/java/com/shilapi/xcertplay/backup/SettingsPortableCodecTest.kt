package com.shilapi.xcertplay.backup

import android.content.Context
import com.shilapi.xcertplay.AirPlayPersistence
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SettingsPortableCodecTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun document(settings: Map<String, JSONObject>, portable: Map<String, JSONObject>): ByteArray {
        val root = JSONObject()
            .put("format", SettingsPortableCodec.FORMAT)
            .put("version", SettingsPortableCodec.VERSION)
            .put("settings", JSONObject(settings))
        val section = JSONObject(portable)
        if (portable.isNotEmpty()) root.put(SettingsPortableCodec.SECTION, section)
        return root.toString().toByteArray()
    }

    @Test
    fun theCarHotspotNameAndPassphraseTravelThroughAFile() {
        AirPlayPersistence.saveManualHotspotSsid(context, "my car")
        AirPlayPersistence.saveManualHotspotPassphrase(context, "12341234")
        val exported = JSONObject(String(SettingsPortableCodec.export(context, "0.2.22")))
        val portable = exported.getJSONObject(SettingsPortableCodec.SECTION).getJSONObject("xcertplay_airplay")
        assertEquals("my car", portable.getString("manual_hotspot_ssid"))
        assertEquals("12341234", portable.getString("manual_hotspot_passphrase"))

        AirPlayPersistence.saveManualHotspotSsid(context, "something else")
        assertFalse(SettingsPortableCodec.import(context, exported.toString().toByteArray()).invalid)
        assertEquals("my car", AirPlayPersistence.loadManualHotspotSsid(context))
        assertEquals("12341234", AirPlayPersistence.loadManualHotspotPassphrase(context))
    }

    @Test
    fun thePageDocumentIsUnchangedAndCarriesNoPortableSection() {
        AirPlayPersistence.saveFps(context, 50)
        AirPlayPersistence.saveManualHotspotSsid(context, "my car")
        val page = JSONObject(String(SettingsBackupCodec.export(context, "0.2.22")))
        assertFalse(page.has(SettingsPortableCodec.SECTION))
        val airplay = page.getJSONObject("settings").getJSONObject("xcertplay_airplay")
        assertEquals(50, airplay.getInt("display_fps"))
        assertFalse(airplay.has("manual_hotspot_ssid"))
        assertFalse(airplay.has("manual_hotspot_passphrase"))
    }

    @Test
    fun aFileRestoresEverythingThePageRestoresAndTheExtraSettings() {
        AirPlayPersistence.saveFps(context, 50)
        AirPlayPersistence.saveDebugLogsEnabled(context, true)
        AirPlayPersistence.saveManualHotspotSsid(context, "my car")
        prefs("diplay_navigation_wheel").edit().putBoolean("enabled", true).commit()
        prefs("side_view").edit().putInt("threshold_kmh", 35).putBoolean("rotate", true).commit()
        prefs("carplay_wifi_p2p_success").edit().putString("confirmed", "system|0|5180|0|uuid").commit()

        val document = SettingsPortableCodec.export(context, "0.2.22")
        AirPlayPersistence.saveFps(context, 60)
        AirPlayPersistence.saveDebugLogsEnabled(context, false)
        AirPlayPersistence.saveManualHotspotSsid(context, "other")
        prefs("diplay_navigation_wheel").edit().putBoolean("enabled", false).commit()
        prefs("side_view").edit().clear().commit()
        prefs("carplay_wifi_p2p_success").edit().clear().commit()

        val outcome = SettingsPortableCodec.import(context, document)
        assertFalse(outcome.invalid)
        assertEquals(0, outcome.skipped)
        assertEquals(50, AirPlayPersistence.loadFps(context))
        assertTrue(AirPlayPersistence.loadDebugLogsEnabled(context))
        assertEquals("my car", AirPlayPersistence.loadManualHotspotSsid(context))
        assertTrue(prefs("diplay_navigation_wheel").getBoolean("enabled", false))
        assertEquals(35, prefs("side_view").getInt("threshold_kmh", 0))
        assertTrue(prefs("side_view").getBoolean("rotate", false))
        assertEquals("system|0|5180|0|uuid", prefs("carplay_wifi_p2p_success").getString("confirmed", null))
        assertTrue(outcome.applied >= 6)
    }

    @Test
    fun pairingKeysAndTheAccessibilityGrantNeverTravel() {
        prefs("xcertplay_airplay").edit()
            .putString("pairing.other", "secret")
            .putString("identity_private", "secret")
            .putString("lockdown_host_private", "secret")
            .commit()
        prefs("diplay_navigation_wheel").edit().putBoolean("authorized", true).commit()
        AirPlayPersistence.saveFps(context, 50)
        AirPlayPersistence.saveManualHotspotSsid(context, "my car")
        val section = JSONObject(String(SettingsPortableCodec.export(context, "0.2.22")))
            .getJSONObject(SettingsPortableCodec.SECTION)
        assertTrue(section.length() > 0)
        for (name in section.keys()) {
            val file = section.getJSONObject(name)
            for (key in file.keys()) {
                assertFalse("$name/$key", key.startsWith("pairing."))
                assertFalse("$name/$key", key.startsWith("identity_"))
                assertFalse("$name/$key", key.startsWith("lockdown_"))
            }
        }
        val airplay = section.optJSONObject("xcertplay_airplay")
        assertNotNull(airplay)
        assertEquals("my car", airplay!!.getString("manual_hotspot_ssid"))
        val wheel = section.optJSONObject("diplay_navigation_wheel")
        assertTrue(wheel == null || !wheel.has("authorized"))
    }

    @Test
    fun anImportRejectsUnknownFilesAndForeignMembers() {
        assertTrue(SettingsPortableCodec.preview(context, ByteArray(512 * 1024 + 1)).invalid)
        assertTrue(SettingsPortableCodec.preview(context, "not json".toByteArray()).invalid)
        assertTrue(
            SettingsPortableCodec.preview(
                context,
                """{"format":"other","version":1,"settings":{}}""".toByteArray(),
            ).invalid,
        )
    }

    @Test
    fun previewCountsTheExtraSettingsAndWritesNothing() {
        val document = document(
            settings = mapOf("diplay" to JSONObject().put("auto_connect", false)),
            portable = mapOf(
                "side_view" to JSONObject().put("threshold_kmh", 12).put("mirror_left", true),
                "xcertplay_airplay" to JSONObject().put("pairing.other", "secret"),
            ),
        )
        val preview = SettingsPortableCodec.preview(context, document)
        assertEquals(3, preview.applied)
        assertEquals(1, preview.skipped)
        assertFalse(prefs("side_view").contains("threshold_kmh"))

        assertEquals(preview, SettingsPortableCodec.import(context, document))
        assertEquals(12, prefs("side_view").getInt("threshold_kmh", 0))
        assertTrue(prefs("side_view").getBoolean("mirror_left", false))
        assertFalse(prefs("xcertplay_airplay").contains("pairing.other"))
    }

    @Test
    fun aValueOfTheWrongTypeIsSkippedInsteadOfCrashing() {
        prefs("side_view").edit().putBoolean("rotate", true).commit()
        val document = document(
            settings = emptyMap(),
            portable = mapOf("side_view" to JSONObject().put("threshold_kmh", "twelve").put("rotate", 7)),
        )
        val outcome = SettingsPortableCodec.import(context, document)
        assertFalse(outcome.invalid)
        assertEquals(0, outcome.applied)
        assertEquals(2, outcome.skipped)
        assertTrue(prefs("side_view").getBoolean("rotate", false))
    }
}
