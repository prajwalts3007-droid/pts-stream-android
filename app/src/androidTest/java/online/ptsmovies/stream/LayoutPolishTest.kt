package online.ptsmovies.stream

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Compiled in the non-emulator audit; execution still requires a real Android device. */
class LayoutPolishTest {
    @get:Rule val ui = createComposeRule()

    @Test fun sortLivesInsideLibraryFiltersAndResetClearsIt() {
        val filter = mutableStateOf(LibraryFilter(year = 2026))
        val sort = mutableStateOf(LibrarySort.RECENT)
        ui.setContent { PtsTheme("dark") {
            LibraryFiltersDialog(emptyList(), filter.value, { filter.value = it }, {}, sort.value, { sort.value = it })
        } }
        ui.onNodeWithContentDescription("Sort").performClick()
        ui.onNodeWithText("Title · Z–A").performClick()
        ui.runOnIdle { assertEquals(LibrarySort.TITLE_DESC, sort.value) }
        ui.onNodeWithText("Reset").performScrollTo().performClick()
        ui.runOnIdle { assertEquals(LibrarySort.RELEVANCE, sort.value); assertEquals(LibraryFilter(), filter.value) }
    }

    @Test fun pairedActionsHaveEqualBoundsOnNormalWidth() {
        ui.setContent { PtsTheme("dark") { Box(Modifier.width(320.dp)) {
            PtsButtonPair(first = { PtsPrimaryButton("Play", it.testTag("first")) {} }, second = { PtsSecondaryButton("My list", it.testTag("second")) {} })
        } } }
        val first = ui.onNodeWithTag("first").fetchSemanticsNode().boundsInRoot
        val second = ui.onNodeWithTag("second").fetchSemanticsNode().boundsInRoot
        assertEquals(first.height, second.height, 1f); assertEquals(first.width, second.width, 1f)
        assertEquals(first.top, second.top, 1f); assertTrue(first.right <= second.left)
    }

    @Test fun largeTextActionsStackWithoutOverlap() {
        ui.setContent { PtsTheme("dark") {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) { Box(Modifier.width(280.dp)) {
                PtsButtonPair(first = { PtsPrimaryButton("Download episodes", it.testTag("first")) {} }, second = { PtsSecondaryButton("Cancel", it.testTag("second")) {} })
            } }
        } }
        val first = ui.onNodeWithTag("first").fetchSemanticsNode().boundsInRoot
        val second = ui.onNodeWithTag("second").fetchSemanticsNode().boundsInRoot
        assertTrue(first.bottom <= second.top); assertEquals(first.width, second.width, 1f)
    }

    @Test fun pageEntryDoesNotDuplicateOrDisposeContentOnRouteChange() {
        val route = mutableStateOf("home")
        val starts = AtomicInteger(); val disposes = AtomicInteger()
        ui.setContent { PtsTheme("dark") { Box(Modifier.ptsPageMotion(route.value)) {
            DisposableEffect(Unit) { starts.incrementAndGet(); onDispose { disposes.incrementAndGet() } }
        } } }
        ui.runOnIdle { route.value = "profile" }
        ui.waitForIdle()
        assertEquals(1, starts.get()); assertEquals(0, disposes.get())
    }
}
