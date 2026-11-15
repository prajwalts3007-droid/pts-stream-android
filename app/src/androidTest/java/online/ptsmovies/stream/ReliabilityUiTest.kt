package online.ptsmovies.stream

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Compiled by CI; real-device execution is explicitly still required. */
class ReliabilityUiTest {
    @get:Rule val ui = createComposeRule()
    @Test fun ringRouteChangesDoNotResizeAvatarAndClicksStayIndependent() {
        val state = mutableStateOf(ConnectionState(ConnectionRoute.LOCAL)); var profile = 0; var details = 0
        ui.setContent { PtsTheme("dark") { CompositionLocalProvider(LocalConnectionDetails provides { details++ }) {
            ProfileAvatar("P", null, null, snapshot = state.value) { profile++ }
        } } }
        val before = ui.onNodeWithTag("profile-avatar").fetchSemanticsNode().boundsInRoot
        ui.onNodeWithTag("profile-avatar").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Local Pi"))
        ui.onNodeWithTag("profile-avatar").performClick()
        ui.onNodeWithTag("profile-avatar").performTouchInput { longClick() }
        ui.runOnIdle { assertEquals(1, profile); assertEquals(1, details); state.value = ConnectionState(ConnectionRoute.INTERNET) }
        ui.onNodeWithTag("profile-avatar").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Internet"))
        assertEquals(before, ui.onNodeWithTag("profile-avatar").fetchSemanticsNode().boundsInRoot)
    }
    @Test fun lateSettingsRefreshDoesNotOverwriteDirtyFormAndSaveIsOneBatch() {
        val prefs = mutableStateOf(JSONObject().put("audio_lang", "en")); var saves = 0; var saved = JSONObject()
        ui.setContent { PtsTheme("dark") { PlaybackPreferencesScreen(prefs.value, { _, _ -> fail("Must batch") }, {}, onSave = { saves++; saved = it }, owner = 42) } }
        ui.onAllNodes(hasSetTextAction())[0].performTextReplacement("hi")
        ui.runOnIdle { prefs.value = JSONObject().put("audio_lang", "ta") }
        ui.onAllNodes(hasSetTextAction())[0].assertTextEquals("hi")
        ui.onNodeWithText("Save").performScrollTo().performClick()
        ui.runOnIdle { assertEquals(1, saves); assertEquals("hi", saved.getString("audio_lang")); assertEquals(4, saved.length()) }
    }
    @Test fun narrowLargeTextRecoveryActionsRemainAvailable() {
        val title = Title(1, "movie", "Long fixture title for narrow screens", 2026, "", "", emptyList(), emptyList(), "hevc", "mp4")
        var recovery: String? = null
        ui.setContent { PtsTheme("dark") { CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
            Box(Modifier.width(288.dp).height(640.dp)) {
                CompactDownloads(emptyList(), listOf(DownloadTask(title, null, "failed", 100, 100, failure = "export")), {}, {}, {}, {}, {}, onRecover = { recovery = it })
            }
        } } }
        ui.onNodeWithText("Retry export / choose storage").performScrollTo().assertIsDisplayed().performClick()
        ui.runOnIdle { assertEquals("1_0", recovery) }
        ui.onNodeWithText("Pause all").performScrollTo().assertIsNotEnabled()
        ui.onNodeWithText("Resume all").assertIsEnabled()
    }
}
