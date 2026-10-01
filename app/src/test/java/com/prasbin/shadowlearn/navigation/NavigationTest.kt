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
    fun hierarchyWeekRouteResolves() {
        assertEquals("hierarchy/week/7", hierarchyWeekRoute(7))
        assertNull(hierarchyWeekRoute(null))
    }
}
