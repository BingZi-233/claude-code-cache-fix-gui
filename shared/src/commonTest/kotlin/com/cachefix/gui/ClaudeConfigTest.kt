package com.cachefix.gui

import com.cachefix.gui.config.ClaudeConfig
import com.cachefix.gui.path.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

class ClaudeConfigTest {
    private val home = "/home/alice"

    @Test
    fun defaultsToDotClaudeWhenEnvUnset() {
        assertEquals(
            Paths.join(home, ".claude"),
            ClaudeConfig.resolveClaudeConfigDir(emptyMap(), home),
        )
    }

    @Test
    fun usesClaudeConfigDirWhenSet() {
        assertEquals(
            Paths.normalize("/custom/claude"),
            ClaudeConfig.resolveClaudeConfigDir(mapOf("CLAUDE_CONFIG_DIR" to "/custom/claude"), home),
        )
    }

    @Test
    fun treatsEmptyClaudeConfigDirAsUnset() {
        assertEquals(
            Paths.join(home, ".claude"),
            ClaudeConfig.resolveClaudeConfigDir(mapOf("CLAUDE_CONFIG_DIR" to ""), home),
        )
    }

    @Test
    fun treatsWhitespaceOnlyAsUnset() {
        assertEquals(
            Paths.join(home, ".claude"),
            ClaudeConfig.resolveClaudeConfigDir(mapOf("CLAUDE_CONFIG_DIR" to "   "), home),
        )
    }

    @Test
    fun configDirOverrideWinsOverEnv() {
        assertEquals(
            Paths.normalize("/from/override"),
            ClaudeConfig.resolveClaudeConfigDir(
                mapOf("CLAUDE_CONFIG_DIR" to "/from/env"),
                home,
                "/from/override",
            ),
        )
    }

    @Test
    fun emptyOverrideFallsThroughToEnv() {
        assertEquals(
            Paths.normalize("/from/env"),
            ClaudeConfig.resolveClaudeConfigDir(
                mapOf("CLAUDE_CONFIG_DIR" to "/from/env"),
                home,
                "",
            ),
        )
    }

    @Test
    fun settingsPathAppendsSettingsJson() {
        val bob = "/home/bob"
        assertEquals(
            Paths.join(bob, ".claude", "settings.json"),
            ClaudeConfig.settingsPath(emptyMap(), bob),
        )
        assertEquals(
            Paths.join(Paths.normalize("/x"), "settings.json"),
            ClaudeConfig.settingsPath(mapOf("CLAUDE_CONFIG_DIR" to "/x"), bob),
        )
    }
}
