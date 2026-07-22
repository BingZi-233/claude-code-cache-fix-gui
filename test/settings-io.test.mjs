import { describe, it, before, after } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, writeFile, mkdir } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import {
  loadSettings,
  saveSettings,
  wireClaudeSettings,
  unwireClaudeSettings,
  resolvePaths,
} from "../src/settings-io.mjs";

describe("settings-io", () => {
  /** @type {string} */
  let dir;

  before(async () => {
    dir = await mkdtemp(join(tmpdir(), "cfgui-settings-"));
  });

  after(async () => {
    await rm(dir, { recursive: true, force: true });
  });

  it("loadSettings returns {} for missing file", async () => {
    const s = await loadSettings(join(dir, "missing.json"));
    assert.deepEqual(s, {});
  });

  it("loadSettings fails closed on non-object JSON", async () => {
    const p = join(dir, "bad.json");
    await writeFile(p, "[1,2]\n");
    await assert.rejects(() => loadSettings(p), /top-level must be a JSON object/);
  });

  it("saveSettings writes atomically and creates .bak", async () => {
    const p = join(dir, "settings.json");
    await saveSettings(p, { a: 1 }, { writeBackup: false });
    await saveSettings(p, { a: 2, env: { X: "1" } }, { writeBackup: true });
    const body = JSON.parse(await readFile(p, "utf8"));
    assert.equal(body.a, 2);
    const bak = JSON.parse(await readFile(`${p}.bak`, "utf8"));
    assert.equal(bak.a, 1);
  });

  it("wire reverse then unwire restores isolation of OTHER keys", async () => {
    const cfg = join(dir, "claude-home");
    await mkdir(cfg, { recursive: true });
    const settingsFile = join(cfg, "settings.json");
    await writeFile(
      settingsFile,
      JSON.stringify({ env: { KEEP: "yes" }, theme: "dark" }, null, 2),
    );

    const wired = await wireClaudeSettings({
      mode: "reverse",
      port: 9801,
      configDirOverride: cfg,
      env: {},
    });
    assert.equal(
      wired.expectedEnv.ANTHROPIC_BASE_URL,
      "http://127.0.0.1:9801",
    );
    const afterWire = JSON.parse(await readFile(settingsFile, "utf8"));
    assert.equal(afterWire.env.ANTHROPIC_BASE_URL, "http://127.0.0.1:9801");
    assert.equal(afterWire.env.KEEP, "yes");
    assert.equal(afterWire.theme, "dark");

    const unwired = await unwireClaudeSettings({
      expectedEnv: wired.expectedEnv,
      configDirOverride: cfg,
      env: {},
    });
    assert.deepEqual(unwired.skipped, []);
    const after = JSON.parse(await readFile(settingsFile, "utf8"));
    assert.equal(after.env.ANTHROPIC_BASE_URL, undefined);
    assert.equal(after.env.KEEP, "yes");
  });

  it("resolvePaths honors configDirOverride for caPem", () => {
    const p = resolvePaths({}, "/home/u", "/custom/root");
    assert.equal(p.configRoot, "/custom/root");
    assert.equal(p.settingsFile, join("/custom/root", "settings.json"));
    assert.ok(p.caPem.endsWith(join("cache-fix-ca", "ca.pem")));
  });
});
