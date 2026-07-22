import { describe, it } from "node:test";
import assert from "node:assert/strict";
import path from "node:path";
import {
  resolveClaudeConfigDir,
  settingsPath,
} from "../src/claude-config.mjs";

describe("resolveClaudeConfigDir", () => {
  const home = "/home/alice";

  it("defaults to ~/.claude when CLAUDE_CONFIG_DIR unset", () => {
    assert.equal(
      resolveClaudeConfigDir({}, home),
      path.join(home, ".claude"),
    );
  });

  it("uses CLAUDE_CONFIG_DIR when set", () => {
    assert.equal(
      resolveClaudeConfigDir({ CLAUDE_CONFIG_DIR: "/custom/claude" }, home),
      path.normalize("/custom/claude"),
    );
  });

  it("treats empty CLAUDE_CONFIG_DIR as unset", () => {
    assert.equal(
      resolveClaudeConfigDir({ CLAUDE_CONFIG_DIR: "" }, home),
      path.join(home, ".claude"),
    );
  });

  it("treats whitespace-only CLAUDE_CONFIG_DIR as unset", () => {
    assert.equal(
      resolveClaudeConfigDir({ CLAUDE_CONFIG_DIR: "   " }, home),
      path.join(home, ".claude"),
    );
  });

  it("configDirOverride wins over env", () => {
    assert.equal(
      resolveClaudeConfigDir(
        { CLAUDE_CONFIG_DIR: "/from/env" },
        home,
        "/from/override",
      ),
      path.normalize("/from/override"),
    );
  });

  it("empty configDirOverride falls through to env", () => {
    assert.equal(
      resolveClaudeConfigDir(
        { CLAUDE_CONFIG_DIR: "/from/env" },
        home,
        "",
      ),
      path.normalize("/from/env"),
    );
  });
});

describe("settingsPath", () => {
  it("appends settings.json to resolved config dir", () => {
    const home = "/home/bob";
    assert.equal(
      settingsPath({}, home),
      path.join(home, ".claude", "settings.json"),
    );
    assert.equal(
      settingsPath({ CLAUDE_CONFIG_DIR: "/x" }, home),
      path.join(path.normalize("/x"), "settings.json"),
    );
  });
});
