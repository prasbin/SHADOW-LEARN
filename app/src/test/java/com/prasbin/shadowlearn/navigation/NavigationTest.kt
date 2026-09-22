package com.prasbin.shadowlearn.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/** Guards the 8-section IA: routes must stay unique and stable. */
class NavigationTest {

    @Test
    fun eightUniqueRoutes() {
        val routes = destinations.map { it.route }
        assertEquals(8, routes.size)
        assertEquals(8, routes.toSet().size)
    }

    @Test
    fun labelsNonBlank() {
        destinations.forEach { assert(it.label.isNotBlank()) }
    }
}
