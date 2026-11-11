package online.ptsmovies.stream

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MotionInteractionTest {
    @get:Rule val ui = createComposeRule()

    @Test fun refreshingStateDisablesDuplicateActionsAndNeverMovesTheButtonBounds() {
        var loading by mutableStateOf(false); var clicks = 0
        ui.setContent { CompositionLocalProvider(LocalPtsMotion provides false) { PtsTheme {
            Column(Modifier.width(320.dp)) {
                PtsRefreshAction("Refresh queue", loading) { clicks++; loading = true }
                PtsRefreshButton("Refresh status", loading, Modifier.fillMaxWidth()) { clicks++; loading = true }
            }
        } } }
        val before = ui.onNodeWithContentDescription("Refresh queue").fetchSemanticsNode().boundsInRoot
        ui.onNodeWithContentDescription("Refresh queue").performClick()
        ui.onNodeWithContentDescription("Refresh queue").assertIsNotEnabled()
        ui.onNodeWithText("Refresh status").assertIsNotEnabled()
        assertEquals("Refreshing", ui.onNodeWithContentDescription("Refresh queue").fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        ui.onNodeWithContentDescription("Refresh queue").performClick()
        ui.runOnIdle { assertEquals(1, clicks); loading = false }
        ui.onNodeWithContentDescription("Refresh queue").assertIsEnabled()
        assertEquals(before, ui.onNodeWithContentDescription("Refresh queue").fetchSemanticsNode().boundsInRoot)
        ui.onNodeWithText("Refresh status").performClick()
        ui.runOnIdle { assertEquals(2, clicks) }
    }

    @Test fun reducedMotionPolicyIsInheritedByPlayerThemeAndUsesImmediateTransitions() {
        assertEquals(0, motionMillis(false, 180)); assertEquals(180, motionMillis(true, 180))
        ui.setContent { CompositionLocalProvider(LocalPtsMotion provides false) { PtsTheme("light") {
            PtsTheme("dark") { Text(if (ptsMotionEnabled()) "Animated" else "Reduced motion") }
        } } }
        ui.onNodeWithText("Reduced motion").assertExists()
        ui.onNodeWithText("Animated").assertDoesNotExist()
    }
}
