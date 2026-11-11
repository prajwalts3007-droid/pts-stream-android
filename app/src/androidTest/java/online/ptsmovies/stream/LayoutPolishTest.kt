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

    @Test fun activeFilterDotIsAccessibleAndDoesNotMoveToolbarActions() {
        val active=mutableStateOf(false)
        ui.setContent { PtsTheme("dark") { Box(Modifier.width(288.dp)) { LibrarySearchBar("",{}, {},"grid",{},active.value) } } }
        val before=ui.onNodeWithTag("library-filter-action").fetchSemanticsNode().boundsInRoot
        ui.onNodeWithTag("active-filter-dot",useUnmergedTree=true).assertDoesNotExist()
        ui.runOnIdle { active.value=true }
        ui.onNodeWithTag("active-filter-dot",useUnmergedTree=true).assertIsDisplayed()
        val after=ui.onNodeWithTag("library-filter-action").fetchSemanticsNode().boundsInRoot
        assertEquals(before,after)
        ui.runOnIdle { active.value=false }
        ui.onNodeWithTag("active-filter-dot",useUnmergedTree=true).assertDoesNotExist()
    }

    @Test fun browseStartsWithSearchAndHasNoDuplicatePageHeading() {
        ui.setContent { PtsTheme("dark") { BrowseScreen("Movies",emptyList(),emptyMap(),emptySet(),"",{}, {},{}) } }
        ui.onNodeWithTag("library-search-row").assertIsDisplayed()
        ui.onNodeWithText("Movies").assertDoesNotExist()
        ui.onNodeWithContentDescription("Library filters").performClick()
        ui.onNodeWithContentDescription("Sort").assertExists()
    }

    @Test fun filesStartsWithDeviceToggleRatherThanBrowseHeading() {
        ui.setContent { PtsTheme("dark") { FilesScreen("vps","",emptyList(),false,{}, {}, {}, {}, {}, {}) } }
        ui.onNodeWithText("Browse").assertDoesNotExist()
        ui.onNodeWithText("VPS").assertIsDisplayed(); ui.onNodeWithText("Raspberry Pi").assertIsDisplayed()
    }

    @Test fun featuredCardAndPlayHaveSameDestinationButMyListRemainsIndependent() {
        val title=Title(9,"movie","Featured example",2026,"","",emptyList(),emptyList(),"hevc","mp4")
        var opened=0; var marked=0
        ui.setContent { PtsTheme("dark") { PtsHero(title,false,{opened++},{opened++},{marked++}) } }
        ui.onNodeWithText("Featured example").performClick()
        ui.onNodeWithText("Play").performClick()
        ui.onNodeWithText("My list").performClick()
        ui.runOnIdle { assertEquals(2,opened); assertEquals(1,marked) }
    }

    @Test fun libraryIconsStayBesideSearchWithoutVisibleLabels() {
        val query = mutableStateOf("")
        val view = mutableStateOf("grid")
        var filtersOpened = false
        ui.setContent { PtsTheme("dark") { Box(Modifier.width(288.dp)) {
            LibrarySearchBar(query.value, { query.value = it }, { filtersOpened = true }, view.value, { view.value = it })
        } } }
        val search = ui.onNodeWithTag("library-search-input").fetchSemanticsNode().boundsInRoot
        val filter = ui.onNodeWithTag("library-filter-action").fetchSemanticsNode().boundsInRoot
        val toggle = ui.onNodeWithTag("library-view-action").fetchSemanticsNode().boundsInRoot
        assertTrue(search.right <= filter.left); assertTrue(filter.right <= toggle.left)
        assertEquals(search.center.y, filter.center.y, 1f); assertEquals(search.center.y, toggle.center.y, 1f)
        ui.onNodeWithText("Filters").assertDoesNotExist()
        ui.onNodeWithText("Library filters").assertDoesNotExist()
        ui.onNodeWithContentDescription("Library filters").performClick()
        ui.runOnIdle { assertTrue(filtersOpened) }
        ui.onNodeWithContentDescription("Switch to list view").performClick()
        ui.runOnIdle { assertEquals("list", view.value) }
        ui.onNodeWithContentDescription("Switch to grid view").performClick()
        ui.onNode(hasSetTextAction()).performTextInput("Signal")
        ui.runOnIdle { assertEquals("grid", view.value); assertEquals("Signal", query.value) }
    }

    @Test fun homeSearchShowsOnlyItsFilterIconBesideSearch() {
        ui.setContent { PtsTheme("dark") { Box(Modifier.width(288.dp)) { LibrarySearchBar("", {}, {}) } } }
        ui.onNodeWithContentDescription("Library filters").assertIsDisplayed()
        ui.onNodeWithTag("library-view-action").assertDoesNotExist()
        ui.onNodeWithText("Relevance").assertDoesNotExist()
    }

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
