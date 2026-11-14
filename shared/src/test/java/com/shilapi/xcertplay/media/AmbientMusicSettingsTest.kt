package com.shilapi.xcertplay.media

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AmbientMusicSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("ambient_music", Context.MODE_PRIVATE)

    @Before fun clear() { prefs.edit().clear().commit() }

    // The CN build ships the lamps on, at full brightness, with a beat-driven album palette.
    @Test fun missingPreferencesUseTheCnDefaults() {
        val values = AmbientMusicSettings.load(context)
        assertTrue(values.enabled)
        assertTrue(values.music)
        assertEquals(listOf(1), values.selectedColors)
        assertEquals(6, values.brightness)
        assertEquals(3, values.area)
        assertEquals(AmbientColorMode.BEAT, values.colorMode)
        assertEquals(AmbientColorSpeed.FAST, values.speed)
        assertEquals(AmbientColorSource.ALBUM, values.colorSource)
        assertEquals(AmbientIdleBehavior.CAR, values.idle)
    }

    @Test fun theCarsOwnRhythmIsOffUntilSavedOnAndSurvivesASave() {
        assertFalse(AmbientMusicSettings.load(context).carRhythm)
        AmbientMusicSettings.save(context, AmbientMusicSettings.load(context).copy(carRhythm = true))
        assertTrue(AmbientMusicSettings.load(context).carRhythm)
        // Saving the lamp settings keeps it.
        AmbientMusicSettings.save(context, AmbientMusicSettings.load(context).copy(brightness = 4))
        assertTrue(AmbientMusicSettings.load(context).carRhythm)
    }

    @Test fun oldSingleColorSettingsSupplyMissingPaletteAndModes() {
        prefs.edit().putInt("color", 12).putInt("brightness", 4).commit()
        val values = AmbientMusicSettings.load(context)
        assertTrue(values.enabled)
        assertEquals(AmbientColorSource.ALBUM, values.colorSource)
        assertEquals(listOf(12), values.selectedColors)
        assertEquals(AmbientColorMode.BEAT, values.colorMode)
        assertEquals(AmbientColorSpeed.FAST, values.speed)
    }

    @Test fun malformedPreferencesUseSafeDefaultsAndValidBounds() {
        prefs.edit().putString("enabled", "true").putString("music", "false")
            .putInt("color", 90).putInt("brightness", -2).putInt("area", 8)
            .putInt("selectedColors", 8).putString("colorMode", "UNKNOWN")
            .putString("speed", "UNKNOWN").putString("colorSource", "UNKNOWN").commit()
        val values = AmbientMusicSettings.load(context)
        assertTrue(values.enabled)
        assertTrue(values.music)
        assertEquals(AmbientColorSource.ALBUM, values.colorSource)
        assertEquals(listOf(31), values.selectedColors)
        assertEquals(0, values.brightness)
        assertEquals(3, values.area)
        assertEquals(AmbientColorMode.BEAT, values.colorMode)
        assertEquals(AmbientColorSpeed.FAST, values.speed)
        assertEquals(AmbientIdleBehavior.CAR, values.idle)
    }

    @Test fun carRhythmFeedRoundTripsAndDefaultsOff() {
        assertFalse(AmbientMusicSettings.load(context).carRhythmFeed)
        AmbientMusicSettings.save(context, AmbientMusicSettings.Values(carRhythm = true, carRhythmFeed = true))
        assertTrue(AmbientMusicSettings.load(context).carRhythmFeed)
        AmbientMusicSettings.save(context, AmbientMusicSettings.Values(carRhythm = true))
        assertFalse(AmbientMusicSettings.load(context).carRhythmFeed)
    }

    @Test fun idleChoiceRoundTripsAndUnknownValuesFallBackToTheCarBehavior() {
        AmbientMusicSettings.save(context, AmbientMusicSettings.Values(idle = AmbientIdleBehavior.STEADY))
        assertEquals(AmbientIdleBehavior.STEADY, AmbientMusicSettings.load(context).idle)
        prefs.edit().putString("idle", "UNKNOWN").commit()
        assertEquals(AmbientIdleBehavior.CAR, AmbientMusicSettings.load(context).idle)
    }
}
