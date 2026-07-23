package com.cachefix.gui.path

/**
 * POSIX-style path helpers matching Node.js `path.normalize` / `path.join` semantics
 * used by the original control-plane (tests use absolute Unix paths).
 */
object Paths {
    fun normalize(path: String): String {
        if (path.isEmpty()) return "."
        val isAbs = path.startsWith("/")
        val trailingSlash = path.length > 1 && path.endsWith("/")
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
        val stack = ArrayDeque<String>()
        for (p in parts) {
            if (p == "..") {
                if (stack.isNotEmpty() && stack.last() != "..") {
                    stack.removeLast()
                } else if (!isAbs) {
                    stack.addLast("..")
                }
            } else {
                stack.addLast(p)
            }
        }
        var result = stack.joinToString("/")
        if (isAbs) result = "/$result"
        if (result.isEmpty()) result = if (isAbs) "/" else "."
        if (trailingSlash && result != "/") result = "$result/"
        // Node path.normalize collapses multiple slashes but keeps absolute root
        return result
    }

    fun join(vararg parts: String): String {
        if (parts.isEmpty()) return "."
        val joined = parts
            .filter { it.isNotEmpty() }
            .joinToString("/") { it.trimEnd('/') }
            .replace(Regex("/+"), "/")
        return normalize(joined)
    }
}
