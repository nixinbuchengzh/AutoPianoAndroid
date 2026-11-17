package com.autopiano.engine

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CalibrationProfileTest {
    private val preset = Keymaps.GENSHIN_3ROW

    @Test fun landscapeDefaultsAreInsideLandscapeScreen() {
        val profile = CalibrationProfile.defaultFor(preset, 2400, 1080, 1)
        assertTrue(profile.fitsViewport(0f, 0f, 2400, 1080))
        assertEquals(2400, profile.screenWidth)
        assertEquals(1080, profile.screenHeight)
        assertEquals(1, profile.rotation)
        assertTrue(profile.top < 1080 && profile.bottom < 1080)
    }

    @Test fun portraitCoordinatesAreRepositionedForLandscapeViewport() {
        val portrait = CalibrationProfile.defaultFor(preset, 1080, 2400)
        assertFalse(portrait.fitsViewport(0f, 0f, 2400, 1080))
        val adjusted = portrait.forViewport(60f, 140f, 2220, 820)
        assertTrue(adjusted.fitsViewport(60f, 140f, 2220, 820))
        assertTrue(adjusted.top >= 140f)
        assertTrue(adjusted.bottom <= 960f)
    }

    @Test fun systemBarAndCutoutOffsetsAreRespected() {
        val hiddenBehindToolbar = CalibrationProfile(preset.id, 0f, 0f, 900f, 700f)
        val adjusted = hiddenBehindToolbar.forViewport(96f, 144f, 2100, 800)
        assertTrue(adjusted.fitsViewport(96f, 144f, 2100, 800))
        assertTrue(adjusted.left >= 96f)
        assertTrue(adjusted.top >= 144f)
    }

    @Test fun existingVisibleCalibrationIsNotChanged() {
        val profile = CalibrationProfile(preset.id, 300f, 450f, 1900f, 900f, 2400, 1080, 1)
        assertSame(profile, profile.forViewport(60f, 140f, 2220, 820))
    }

    @Test fun invalidOrReversedCoordinatesAreRejected() {
        assertFalse(CalibrationProfile(preset.id, Float.NaN, 100f, 900f, 700f).fitsViewport(0f, 0f, 2400, 1080))
        assertFalse(CalibrationProfile(preset.id, 900f, 700f, 100f, 100f).fitsViewport(0f, 0f, 2400, 1080))
        assertFalse(CalibrationProfile(preset.id, 100f, 100f, 120f, 120f).fitsViewport(0f, 0f, 2400, 1080))
        assertFalse(CalibrationProfile(preset.id, -1f, 100f, 900f, 700f).fitsViewport(0f, 0f, 2400, 1080))
    }

    @Test fun serializationIncludesScreenDimensionsAndRotation() {
        val profile = CalibrationProfile.defaultFor(preset, 2400, 1080, 3)
        val json = JSONObject(profile.toJson())
        assertEquals(2400, json.getInt("screenWidth"))
        assertEquals(1080, json.getInt("screenHeight"))
        assertEquals(3, json.getInt("rotation"))
        assertEquals(preset.id, json.getString("presetId"))
        assertEquals(profile.bottom.toDouble(), json.getDouble("bottom"), 0.001)
    }
}
