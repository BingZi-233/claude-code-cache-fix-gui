package com.cachefix.gui

import com.cachefix.gui.controller.Controller
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Windows `where` / cmd noise must never be treated as filesystem paths
 * (caused: Illegal char <:> at index 4: INFO: Could not find files...).
 */
class PathParseTest {
    @Test
    fun rejectsWhereNotFoundMessage() {
        val noise = "INFO: Could not find files for the given pattern(s)."
        assertNull(Controller.firstExistingPathLine(noise))
        assertFalse(Controller.looksLikeFsPath(noise))
    }

    @Test
    fun rejectsMixedNoiseWithBlankLines() {
        val out = """
            
            INFO: Could not find files for the given pattern(s).
            
        """.trimIndent()
        assertNull(Controller.firstExistingPathLine(out))
    }

    @Test
    fun acceptsWindowsDrivePathShape() {
        assertTrue(Controller.looksLikeFsPath("C:\\Users\\ziyou\\bin\\cache-fix-proxy.exe"))
        assertTrue(Controller.looksLikeFsPath("C:/Users/ziyou/bin/node.exe"))
    }

    @Test
    fun acceptsUncShape() {
        assertTrue(Controller.looksLikeFsPath("\\\\server\\share\\tool.exe"))
    }

    @Test
    fun acceptsPosixAbsolute() {
        assertTrue(Controller.looksLikeFsPath("/usr/bin/node"))
    }
}
