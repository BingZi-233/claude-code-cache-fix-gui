package com.cachefix.gui

import com.cachefix.gui.path.Paths
import com.cachefix.gui.settings.DEFAULT_PORT
import com.cachefix.gui.settings.ProxyMode
import com.cachefix.gui.settings.applyClaudeEnv
import com.cachefix.gui.settings.computeExpectedEnv
import com.cachefix.gui.settings.mergeNoProxy
import com.cachefix.gui.settings.removeClaudeEnv
import com.cachefix.gui.settings.stripLocalhostNoProxy
import com.cachefix.gui.settings.validatePort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsEnvTest {
    @Test
    fun validatePortAcceptsRange() {
        assertEquals("1", validatePort(1))
        assertEquals("9801", validatePort(9801))
        assertEquals("65535", validatePort(65535))
        assertEquals("8080", validatePort("8080"))
    }

    @Test
    fun validatePortRejectsInvalid() {
        assertFailsWith<IllegalArgumentException> { validatePort(0) }
        assertFailsWith<IllegalArgumentException> { validatePort(65536) }
        assertFailsWith<IllegalArgumentException> { validatePort(-1) }
        assertFailsWith<IllegalArgumentException> { validatePort("abc") }
        assertFailsWith<IllegalArgumentException> { validatePort("80.5") }
        assertFailsWith<IllegalArgumentException> { validatePort(null) }
    }

    @Test
    fun mergeNoProxyAppendsLocalhosts() {
        assertEquals("127.0.0.1,localhost,::1", mergeNoProxy(null))
        assertEquals(
            "corp.example,127.0.0.1,localhost,::1",
            mergeNoProxy("corp.example"),
        )
        assertEquals(
            "127.0.0.1,corp.example,localhost,::1",
            mergeNoProxy("127.0.0.1,corp.example"),
        )
    }

    @Test
    fun stripLocalhostNoProxyKeepsCorp() {
        assertEquals(
            "corp.example",
            stripLocalhostNoProxy("corp.example,127.0.0.1,localhost,::1"),
        )
        assertNull(stripLocalhostNoProxy("127.0.0.1,localhost,::1"))
        assertNull(stripLocalhostNoProxy(null))
    }

    @Test
    fun computeExpectedEnvReverseDefaultPort() {
        val env = computeExpectedEnv(ProxyMode.REVERSE)
        assertEquals(mapOf("ANTHROPIC_BASE_URL" to "http://127.0.0.1:$DEFAULT_PORT"), env)
    }

    @Test
    fun computeExpectedEnvForward() {
        val ca = "/home/u/.claude/cache-fix-ca/ca.pem"
        val env = computeExpectedEnv(
            ProxyMode.FORWARD,
            port = 9801,
            caPemPath = ca,
            existingNoProxy = "corp.example",
        )
        assertEquals("http://127.0.0.1:9801", env["HTTPS_PROXY"])
        assertEquals("http://127.0.0.1:9801", env["https_proxy"])
        assertEquals(Paths.normalize(ca), env["NODE_EXTRA_CA_CERTS"])
        assertEquals("corp.example,127.0.0.1,localhost,::1", env["NO_PROXY"])
        assertEquals(env["NO_PROXY"], env["no_proxy"])
    }

    @Test
    fun forwardRequiresCaPemPath() {
        assertFailsWith<IllegalArgumentException> {
            computeExpectedEnv(ProxyMode.FORWARD, port = 1)
        }
    }

    @Test
    fun applyClaudeEnvReverseStripsMatchingForwardKeys() {
        val ca = Paths.normalize("/tmp/ca.pem")
        val settings = mapOf(
            "env" to mapOf(
                "HTTPS_PROXY" to "http://127.0.0.1:9801",
                "https_proxy" to "http://127.0.0.1:9801",
                "NODE_EXTRA_CA_CERTS" to ca,
                "NO_PROXY" to "corp.example,127.0.0.1,localhost,::1",
                "no_proxy" to "corp.example,127.0.0.1,localhost,::1",
                "OTHER" to "keep-me",
            ),
            "model" to "claude",
        )
        val result = applyClaudeEnv(settings, ProxyMode.REVERSE, port = 9801, caPemPath = ca)
        @Suppress("UNCHECKED_CAST")
        val env = result.nextSettings["env"] as Map<String, String>
        assertEquals("http://127.0.0.1:9801", env["ANTHROPIC_BASE_URL"])
        assertEquals("keep-me", env["OTHER"])
        assertTrue("HTTPS_PROXY" !in env)
        assertTrue("https_proxy" !in env)
        assertEquals("corp.example", env["NO_PROXY"])
        assertEquals(mapOf("ANTHROPIC_BASE_URL" to "http://127.0.0.1:9801"), result.expectedEnv)
    }

    @Test
    fun applyClaudeEnvForwardSnapshotsAnthropicBaseUrl() {
        val ca = Paths.normalize("/tmp/ca.pem")
        val settings = mapOf(
            "env" to mapOf(
                "ANTHROPIC_BASE_URL" to "https://api.anthropic.com",
                "NO_PROXY" to "corp.example",
            ),
        )
        val result = applyClaudeEnv(settings, ProxyMode.FORWARD, port = 9801, caPemPath = ca)
        @Suppress("UNCHECKED_CAST")
        val env = result.nextSettings["env"] as Map<String, String>
        assertTrue("ANTHROPIC_BASE_URL" !in env)
        assertEquals("https://api.anthropic.com", result.anthropicBaseUrlBackup)
        assertEquals("http://127.0.0.1:9801", env["HTTPS_PROXY"])
    }

    @Test
    fun removeClaudeEnvExactMatchAndRestoreBackup() {
        val expected = mapOf("ANTHROPIC_BASE_URL" to "http://127.0.0.1:9801")
        val settings = mapOf(
            "env" to mapOf(
                "ANTHROPIC_BASE_URL" to "http://127.0.0.1:9801",
                "OTHER" to "x",
            ),
        )
        val result = removeClaudeEnv(
            settings,
            expected,
            anthropicBaseUrlBackup = "https://backup.example",
        )
        @Suppress("UNCHECKED_CAST")
        val env = result.nextSettings["env"] as Map<String, String>
        assertEquals("https://backup.example", env["ANTHROPIC_BASE_URL"])
        assertEquals("x", env["OTHER"])
        assertNull(result.anthropicBaseUrlBackup)
    }

    @Test
    fun removeClaudeEnvSkipsUserModifiedKeys() {
        val expected = mapOf("ANTHROPIC_BASE_URL" to "http://127.0.0.1:9801")
        val settings = mapOf(
            "env" to mapOf("ANTHROPIC_BASE_URL" to "http://user-changed:9"),
        )
        val result = removeClaudeEnv(settings, expected)
        assertEquals(listOf("ANTHROPIC_BASE_URL"), result.skipped)
        @Suppress("UNCHECKED_CAST")
        val env = result.nextSettings["env"] as Map<String, String>
        assertEquals("http://user-changed:9", env["ANTHROPIC_BASE_URL"])
    }

    @Test
    fun reverseSnapshotsAnthropicBaseUrlBeforeOverwrite() {
        val settings = mapOf(
            "env" to mapOf(
                "ANTHROPIC_BASE_URL" to "https://gateway.example.com",
                "OTHER" to "keep",
            ),
        )
        val result = applyClaudeEnv(settings, ProxyMode.REVERSE, port = 9801)
        @Suppress("UNCHECKED_CAST")
        val env = result.nextSettings["env"] as Map<String, String>
        assertEquals("http://127.0.0.1:9801", env["ANTHROPIC_BASE_URL"])
        assertEquals("https://gateway.example.com", result.anthropicBaseUrlBackup)
        assertEquals("keep", env["OTHER"])

        val removed = removeClaudeEnv(
            result.nextSettings,
            result.expectedEnv,
            anthropicBaseUrlBackup = result.anthropicBaseUrlBackup,
        )
        @Suppress("UNCHECKED_CAST")
        val restored = removed.nextSettings["env"] as Map<String, String>
        assertEquals("https://gateway.example.com", restored["ANTHROPIC_BASE_URL"])
        assertEquals("keep", restored["OTHER"])
        assertNull(removed.anthropicBaseUrlBackup)
    }

    @Test
    fun reverseDoesNotBackupWhenAlreadyLocalProxy() {
        val settings = mapOf(
            "env" to mapOf("ANTHROPIC_BASE_URL" to "http://127.0.0.1:9801"),
        )
        val result = applyClaudeEnv(
            settings,
            ProxyMode.REVERSE,
            port = 9801,
            anthropicBaseUrlBackup = "https://prior-backup.example",
        )
        // Keep prior backup; do not overwrite with local proxy URL
        assertEquals("https://prior-backup.example", result.anthropicBaseUrlBackup)
        assertEquals("http://127.0.0.1:9801", (result.nextSettings["env"] as Map<*, *>)["ANTHROPIC_BASE_URL"])
    }

    @Test
    fun reverseAlsoSetsFoundryWhenOriginallyPresent() {
        val settings = mapOf(
            "env" to mapOf(
                "ANTHROPIC_BASE_URL" to "https://api.anthropic.com",
                "ANTHROPIC_FOUNDRY_BASE_URL" to "https://foundry.example.com",
            ),
        )
        val result = applyClaudeEnv(settings, ProxyMode.REVERSE, port = 9801)
        @Suppress("UNCHECKED_CAST")
        val env = result.nextSettings["env"] as Map<String, String>
        assertEquals("http://127.0.0.1:9801", env["ANTHROPIC_BASE_URL"])
        assertEquals("http://127.0.0.1:9801", env["ANTHROPIC_FOUNDRY_BASE_URL"])
        assertEquals("http://127.0.0.1:9801", result.expectedEnv["ANTHROPIC_FOUNDRY_BASE_URL"])
        assertEquals("https://api.anthropic.com", result.anthropicBaseUrlBackup)
        assertEquals("https://foundry.example.com", result.anthropicFoundryBaseUrlBackup)
    }

    @Test
    fun reverseDoesNotTouchFoundryWhenAbsent() {
        val settings = mapOf(
            "env" to mapOf("ANTHROPIC_BASE_URL" to "https://api.anthropic.com"),
        )
        val result = applyClaudeEnv(settings, ProxyMode.REVERSE, port = 9801)
        @Suppress("UNCHECKED_CAST")
        val env = result.nextSettings["env"] as Map<String, String>
        assertTrue("ANTHROPIC_FOUNDRY_BASE_URL" !in env)
        assertTrue("ANTHROPIC_FOUNDRY_BASE_URL" !in result.expectedEnv)
        assertNull(result.anthropicFoundryBaseUrlBackup)
    }

    @Test
    fun forwardSnapshotsAndRemovesFoundry() {
        val ca = Paths.normalize("/tmp/ca.pem")
        val settings = mapOf(
            "env" to mapOf(
                "ANTHROPIC_BASE_URL" to "https://api.anthropic.com",
                "ANTHROPIC_FOUNDRY_BASE_URL" to "https://foundry.example.com",
            ),
        )
        val result = applyClaudeEnv(settings, ProxyMode.FORWARD, port = 9801, caPemPath = ca)
        @Suppress("UNCHECKED_CAST")
        val env = result.nextSettings["env"] as Map<String, String>
        assertTrue("ANTHROPIC_BASE_URL" !in env)
        assertTrue("ANTHROPIC_FOUNDRY_BASE_URL" !in env)
        assertEquals("https://api.anthropic.com", result.anthropicBaseUrlBackup)
        assertEquals("https://foundry.example.com", result.anthropicFoundryBaseUrlBackup)
    }

    @Test
    fun unwireRestoresFoundryBackup() {
        val expected = mapOf(
            "ANTHROPIC_BASE_URL" to "http://127.0.0.1:9801",
            "ANTHROPIC_FOUNDRY_BASE_URL" to "http://127.0.0.1:9801",
        )
        val settings = mapOf(
            "env" to mapOf(
                "ANTHROPIC_BASE_URL" to "http://127.0.0.1:9801",
                "ANTHROPIC_FOUNDRY_BASE_URL" to "http://127.0.0.1:9801",
            ),
        )
        val result = removeClaudeEnv(
            settings,
            expected,
            anthropicBaseUrlBackup = "https://api.anthropic.com",
            anthropicFoundryBaseUrlBackup = "https://foundry.example.com",
        )
        @Suppress("UNCHECKED_CAST")
        val env = result.nextSettings["env"] as Map<String, String>
        assertEquals("https://api.anthropic.com", env["ANTHROPIC_BASE_URL"])
        assertEquals("https://foundry.example.com", env["ANTHROPIC_FOUNDRY_BASE_URL"])
        assertNull(result.anthropicBaseUrlBackup)
        assertNull(result.anthropicFoundryBaseUrlBackup)
    }
}
