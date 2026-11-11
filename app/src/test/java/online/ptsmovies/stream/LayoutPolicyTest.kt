package online.ptsmovies.stream

import org.junit.Assert.*
import org.junit.Test

class LayoutPolicyTest {
    @Test fun normalPhoneUsesEqualSideBySideActions() { assertFalse(stackActions(320f, 1f)) }
    @Test fun narrowScreenStacksActions() { assertTrue(stackActions(240f, 1f)) }
    @Test fun largeTextStacksBeforeButtonsCanCollide() { assertTrue(stackActions(320f, 1.5f)); assertTrue(stackActions(420f, 2f)) }
    @Test fun reducedFontDoesNotMakeTouchTargetsSmaller() { assertEquals(stackActions(270f, 1f), stackActions(270f, .8f)) }
    @Test fun normalLibraryUsesTwoColumnsAndLargeTextUsesOne() { assertEquals(2, libraryColumns(328f, 1f, false)); assertEquals(1, libraryColumns(328f, 1.8f, false)) }
    @Test fun wideLibraryAddsColumnsButNeverMoreThanSix() { assertEquals(4, libraryColumns(640f, 1f, false)); assertEquals(6, libraryColumns(2400f, 1f, false)) }
    @Test fun listModeAlwaysRemainsOneColumn() { assertEquals(1, libraryColumns(1200f, 1f, true)) }
    @Test fun tinyWindowNeverCreatesZeroGridColumns() { assertEquals(1, libraryColumns(100f, 2f, false)) }
    @Test fun miniPlayerReservesTextSpaceOnNarrowOrLargeTextScreens() { assertTrue(compactMiniActions(320f, 1f)); assertTrue(compactMiniActions(480f, 1.5f)); assertFalse(compactMiniActions(480f, 1f)) }
    @Test fun reducedMotionRemovesCustomAnimationDurations() { assertEquals(0, motionMillis(false, 220)); assertEquals(220, motionMillis(true, 220)) }
}
