# Design review: claude-code-cache-fix GUI

**Doc:** `docs/design/2026-07-22-gui-design.md`  
**Date:** 2026-07-22  
**Reviewer:** Review Agent  
**Upstream checked:** `cnighswonger/claude-code-cache-fix` @ local tree (package `4.3.0`, `proxy/server.mjs` `/health`, `bin/claude-via-proxy.mjs` forward wiring, `proxy/config.mjs` CA dir)

## Verdict: FAIL

Product locks are present and aligned with upstream env contracts at a high level, but load-bearing **settings.json mutation** and **proxy process env inheritance** are underspecified in ways that cause user config data loss or CA/config-root desync if implemented as written. Fix blockers before pure-logic slice.

---

## Product lock checklist

| Lock | Covered? | Design ref |
|------|----------|------------|
| Tauri 2 | Yes | §2 Tech stack, §7 |
| Tray + control panel | Yes | §2 Surface, §4.1–4.2 |
| Hybrid npm → sidecar | Yes | §2 Proxy binary source, §6 |
| Reverse default + forward | Yes | §2 Proxy modes, §5.3 |
| `CLAUDE_CONFIG_DIR` + `{root}/settings.json` | Yes | §2, §5.1 |
| No Claude launch | Yes | §2 Claude integration, §2 non-goals |
| Independent repo `BingZi-233` | Yes | §2 Repo model / GitHub owner |

All seven product locks are explicitly locked. Non-goals (Linux, project settings, launch Claude, monorepo merge) clear.

Upstream contract claims that check out:

- Process: `cache-fix-proxy server` exists (`bin/claude-via-proxy.mjs` subcommand `server`)
- Health: `GET /health` → `{status, version, forward_proxy, https_proxy}` (200 ok / 503 degraded)
- Env: `CACHE_FIX_PROXY_PORT`, `CACHE_FIX_FORWARD_PROXY=on`, `CACHE_FIX_CA_DIR`
- Reverse client: `ANTHROPIC_BASE_URL=http://127.0.0.1:<port>`
- Forward client: `HTTPS_PROXY` + `NODE_EXTRA_CA_CERTS`; leave `ANTHROPIC_BASE_URL` unset; merge `NO_PROXY` localhost list
- CA path: `CACHE_FIX_CA_DIR || join(claudeHome(), "cache-fix-ca")/ca.pem`
- Compatible range `>=4.3.0 <5` matches current upstream `4.3.0`

---

## Findings

### 1. blocking — §5.3 / §5.4 — irreversible `ANTHROPIC_BASE_URL` delete on forward wire

**Problem:** Forward apply says **Delete** `ANTHROPIC_BASE_URL` if present. Upstream launcher does the same for a *process env of one spawn*. GUI writes **persistent** `settings.json`. User with a non-proxy base URL (corp gateway, custom relay) loses that value forever on forward wire or mode switch; unwire cannot restore it.

**Fix:** Before delete, snapshot prior value into GUI app config. On unwire / switch back to reverse (or explicit restore), reapply snapshot if still absent. Pure API e.g. `applyClaudeEnv` returns `{ nextSettings, backup }` / accepts `restore`. Unit test: custom base URL → forward → unwire restores original.

### 2. blocking — §5.3 / §5.4 / §10 — `NO_PROXY` merge/unmerge contract incomplete

**Problem:** Apply merges localhost hosts into existing `NO_PROXY`. Remove as whole-string match never equals “what GUI last wrote” alone. Risk: wipe entire `NO_PROXY` or never remove localhost entries on unwire.

**Fix:** Spec pure ops: apply merges host set; remove strips only the three localhost tokens; delete key only if empty after strip. Unit tests with corp `NO_PROXY`.

### 3. blocking — §5.1 / §6 — child proxy env must inherit effective config root

**Problem:** Spawned proxy may generate CA under `~/.claude` while settings point at override root.

**Fix:** Always set `CLAUDE_CONFIG_DIR=<effectiveConfigRoot>` on spawn; for forward set `CACHE_FIX_CA_DIR` to the same resolved `caDir` used by apply.

### 4. major — §4.3 — `/health` degraded (HTTP 503) not modeled

Parse JSON body. `status=="ok"` → Running; `status=="degraded"` → Degraded; network/non-JSON → failure counter.

### 5. major — §4.3 — foreign listener attach is a trust decision

Require `version` + status in `{ok,degraded}`; label “attached (not managed)”; never auto-wire without confirm; Stop only for GUI-spawned PIDs.

### 6. major — §7 — OpenSSL dependency for CA generation

Document/detect openssl before offering forward; refuse wire until health says `forward_proxy:true`.

### 7. major — §5.2 / §8 — settings write lacks backup

Write `settings.json.bak` before first GUI mutate per session.

### 8–10. nit — dual-case env keys; create `env` if missing; test gaps

---

## Security notes

settings.json: mutate only owned keys; no full-file logging; fail closed; atomic write. Gaps: ANTHROPIC snapshot, NO_PROXY unwire, backup.

MITM CA: only write `NODE_EXTRA_CA_CERTS` → ca.pem; never ca.key; align CA dir with effective config root.

---

## Blocking vs ship

| ID | Severity | Section |
|----|----------|---------|
| 1 | blocking | §5.3–5.4 ANTHROPIC snapshot/restore |
| 2 | blocking | §5.3–5.4 NO_PROXY unmerge |
| 3 | blocking | §5.1/§6 child CLAUDE_CONFIG_DIR/CA env |
| 4–7 | major | health degraded, attach trust, openssl, backup |

**No blocking findings** would require irreversible settings mutation and process/config-root alignment to be fully specified. They were not at review time.

---

## Summary

Design is the right product shape. **FAIL** on three load-bearing spec holes: persistent ANTHROPIC delete without restore, NO_PROXY unmerge, and spawn env inheritance for effective config root/CA.

— Review Agent
