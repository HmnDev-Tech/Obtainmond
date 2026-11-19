package io.obtainmond.data.repo

import io.obtainmond.data.model.ObtainmondSettings
import io.obtainmond.data.model.ReleaseNotesExpansion
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesExpansionTest {
    @Test
    fun previousSettingsStartExpandedAndRememberFutureChoices() {
        val settings = Json.decodeFromString<ObtainmondSettings>("{}")
        assertEquals(ReleaseNotesExpansion.REMEMBER_LAST_STATE, settings.releaseNotesExpansion)
        assertTrue(settings.lastReleaseNotesExpanded)

        val saved = Json.encodeToString(settings.copy(lastReleaseNotesExpanded = false))
        val restored = Json.decodeFromString<ObtainmondSettings>(saved)
        assertFalse(restored.releaseNotesExpansion.initiallyExpanded(restored.lastReleaseNotesExpanded))
    }

    @Test
    fun fixedModesIgnoreLastState() {
        assertTrue(ReleaseNotesExpansion.ALWAYS_EXPANDED.initiallyExpanded(false))
        assertFalse(ReleaseNotesExpansion.ALWAYS_COLLAPSED.initiallyExpanded(true))
    }
}
