package com.prasbin.shadowlearn.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Guards the 5-group SYSTEM IA: tabs unique, every legacy route resolves. */
class NavigationTest {

    @Test
    fun fiveUniqueTabsWithDefaults() {
        val tabs = NavTab.entries
        assertEquals(5, tabs.size)
        assertEquals(5, tabs.map { it.defaultRoute }.toSet().size)
        tabs.forEach { assert(it.label.isNotBlank()) }
    }

    @Test
    fun topLevelRoutesResolveToGroups() {
        assertEquals(NavTab.HOME, tabForRoute(Routes.DASHBOARD))
        assertEquals(NavTab.ACADEMIC, tabForRoute(Routes.ACADEMIC))
        assertEquals(NavTab.STUDY, tabForRoute(Routes.QUIZ))
        assertEquals(NavTab.STUDY, tabForRoute(Routes.FLASHCARDS))
        assertEquals(NavTab.LISTEN, tabForRoute(Routes.LISTENER))
        assertEquals(NavTab.SYSTEM, tabForRoute(Routes.PROGRESS))
        assertEquals(NavTab.SYSTEM, tabForRoute(Routes.SETTINGS))
    }

    @Test
    fun hierarchyRoutesResolveToAcademic() {
        assertEquals(NavTab.ACADEMIC, tabForRoute(Routes.hierarchyRoot()))
        assertEquals(NavTab.ACADEMIC, tabForRoute(Routes.hierarchyYear(1)))
        assertEquals(NavTab.ACADEMIC, tabForRoute(Routes.hierarchySemester(2)))
        assertEquals(NavTab.ACADEMIC, tabForRoute(Routes.hierarchyModule(3)))
        assertEquals(NavTab.ACADEMIC, tabForRoute(Routes.hierarchyWeek(4)))
    }

    @Test
    fun searchStandsAlone() {
        assertNull(tabForRoute(Routes.SEARCH))
        assertNull(tabForRoute(null))
        assertNull(tabForRoute("unknown"))
    }

    @Test
    fun assistantIsHomeOriginatedNotNewTab() {
        // I8: assistant entry lives on SYSTEM HOME; it must not become a
        // sixth bottom-navigation tab. It is a standalone destination, so no
        // tab is reported as "already open" while it is visible.
        assertNull(tabForRoute(Routes.ASSISTANT))
        assertEquals(5, NavTab.entries.size)
    }

    @Test
    fun homeTabOwnsOnlyDashboard() {
        // Tapping HOME from the assistant must return to the dashboard; the
        // assistant is a separate destination, not HOME's own screen.
        assert(NavTab.HOME.ownsRoute(Routes.DASHBOARD))
        assert(!NavTab.HOME.ownsRoute(Routes.ASSISTANT))
        assert(!NavTab.HOME.ownsRoute(null))
    }

    @Test
    fun groupedTabsKeepTheirInGroupScreens() {
        // In-group switchers stay a no-op so their state is never reset.
        assert(NavTab.ACADEMIC.ownsRoute(Routes.hierarchyWeek(4)))
        assert(NavTab.STUDY.ownsRoute(Routes.FLASHCARDS))
        assert(NavTab.STUDY.ownsRoute(Routes.practiceQuiz(9)))
        assert(NavTab.SYSTEM.ownsRoute(Routes.SETTINGS))
        assert(NavTab.LISTEN.ownsRoute(Routes.LISTENER))
        // A different tab never owns another group's route.
        assert(!NavTab.HOME.ownsRoute(Routes.QUIZ))
        assert(!NavTab.ACADEMIC.ownsRoute(Routes.SEARCH))
    }

    @Test
    fun homeNeverRestoresAssistantStack() {
        // Regression: HOME's saved back stack once contained the assistant,
        // so tapping Home reopened the assistant instead of the dashboard.
        assert(!NavTab.HOME.restoresSavedState())
        // Other groups keep their in-group screen state.
        assert(NavTab.ACADEMIC.restoresSavedState())
        assert(NavTab.STUDY.restoresSavedState())
        assert(NavTab.LISTEN.restoresSavedState())
        assert(NavTab.SYSTEM.restoresSavedState())
    }

    @Test
    fun hierarchyWeekRouteResolves() {
        assertEquals("hierarchy/week/7", hierarchyWeekRoute(7))
        assertNull(hierarchyWeekRoute(null))
    }
}
