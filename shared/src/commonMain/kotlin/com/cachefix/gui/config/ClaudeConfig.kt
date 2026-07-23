package com.cachefix.gui.config

import com.cachefix.gui.path.Paths

/**
 * Claude Code config root + settings path resolution.
 * Pure: no filesystem I/O.
 *
 * @see docs/design/2026-07-22-gui-design.md §5.1
 */
object ClaudeConfig {
    /**
     * Resolve Claude Code config directory.
     *
     * Precedence:
     * 1. non-empty configDirOverride
     * 2. non-empty env CLAUDE_CONFIG_DIR
     * 3. join(homedir, ".claude")
     *
     * Empty string for CLAUDE_CONFIG_DIR is treated as unset.
     */
    fun resolveClaudeConfigDir(
        env: Map<String, String?>,
        homedir: String,
        configDirOverride: String? = null,
    ): String {
        if (configDirOverride != null && configDirOverride.trim().isNotEmpty()) {
            return Paths.normalize(configDirOverride)
        }
        val fromEnv = env["CLAUDE_CONFIG_DIR"]
        if (fromEnv != null && fromEnv.trim().isNotEmpty()) {
            return Paths.normalize(fromEnv)
        }
        return Paths.join(homedir, ".claude")
    }

    /** Path to Claude Code global settings.json. */
    fun settingsPath(
        env: Map<String, String?>,
        homedir: String,
        configDirOverride: String? = null,
    ): String = Paths.join(resolveClaudeConfigDir(env, homedir, configDirOverride), "settings.json")
}
