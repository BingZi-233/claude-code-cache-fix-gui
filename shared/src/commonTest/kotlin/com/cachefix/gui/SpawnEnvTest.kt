package com.cachefix.gui

import com.cachefix.gui.path.Paths
import com.cachefix.gui.settings.ProxyMode
import com.cachefix.gui.spawn.buildProxySpawnEnv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpawnEnvTest {
    private val root = "/home/u/.claude"

    @Test
    fun alwaysIncludesConfigDirCaDirPort() {
        val env = buildProxySpawnEnv(
            port = 9801,
            mode = ProxyMode.REVERSE,
            effectiveConfigRoot = root,
        )
        assertEquals("9801", env["CACHE_FIX_PROXY_PORT"])
        assertEquals(Paths.normalize(root), env["CLAUDE_CONFIG_DIR"])
        assertEquals(Paths.join(root, "cache-fix-ca"), env["CACHE_FIX_CA_DIR"])
        assertNull(env["CACHE_FIX_FORWARD_PROXY"])
    }

    @Test
    fun setsForwardProxyOnlyInForwardMode() {
        val fwd = buildProxySpawnEnv(
            port = 8080,
            mode = ProxyMode.FORWARD,
            effectiveConfigRoot = root,
        )
        assertEquals("on", fwd["CACHE_FIX_FORWARD_PROXY"])
        assertEquals("8080", fwd["CACHE_FIX_PROXY_PORT"])

        val rev = buildProxySpawnEnv(
            mode = ProxyMode.REVERSE,
            effectiveConfigRoot = root,
            baseEnv = mapOf("CACHE_FIX_FORWARD_PROXY" to "on", "PATH" to "/usr/bin"),
        )
        assertNull(rev["CACHE_FIX_FORWARD_PROXY"])
        assertEquals("/usr/bin", rev["PATH"])
    }

    @Test
    fun honorsExplicitCaDir() {
        val env = buildProxySpawnEnv(
            effectiveConfigRoot = root,
            caDir = "/custom/ca",
        )
        assertEquals(Paths.normalize("/custom/ca"), env["CACHE_FIX_CA_DIR"])
    }

    @Test
    fun requiresEffectiveConfigRoot() {
        assertFailsWith<IllegalArgumentException> {
            buildProxySpawnEnv(effectiveConfigRoot = "")
        }
    }

    @Test
    fun mergesExtraEnv() {
        val env = buildProxySpawnEnv(
            port = 9801,
            mode = ProxyMode.REVERSE,
            effectiveConfigRoot = root,
            extraEnv = mapOf(
                "CACHE_FIX_PROXY_UPSTREAM" to "http://127.0.0.1:8080",
                "CACHE_FIX_DEBUG" to "1",
                "HTTPS_PROXY" to "http://corp:3128",
            ),
        )
        assertEquals("http://127.0.0.1:8080", env["CACHE_FIX_PROXY_UPSTREAM"])
        assertEquals("1", env["CACHE_FIX_DEBUG"])
        assertEquals("http://corp:3128", env["HTTPS_PROXY"])
        assertEquals("9801", env["CACHE_FIX_PROXY_PORT"])
    }

    @Test
    fun modeWinsOverExtraEnvForForwardFlag() {
        val env = buildProxySpawnEnv(
            mode = ProxyMode.REVERSE,
            effectiveConfigRoot = root,
            extraEnv = mapOf("CACHE_FIX_FORWARD_PROXY" to "on"),
        )
        assertNull(env["CACHE_FIX_FORWARD_PROXY"])
        assertTrue(env.containsKey("CACHE_FIX_PROXY_PORT"))
    }
}
