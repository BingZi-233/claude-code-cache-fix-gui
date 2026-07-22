import { describe, it } from "node:test";
import assert from "node:assert/strict";
import path from "node:path";
import { buildProxySpawnEnv } from "../src/spawn-env.mjs";

describe("buildProxySpawnEnv", () => {
  const root = "/home/u/.claude";

  it("always includes CLAUDE_CONFIG_DIR + CACHE_FIX_CA_DIR + port", () => {
    const env = buildProxySpawnEnv({
      port: 9801,
      mode: "reverse",
      effectiveConfigRoot: root,
    });
    assert.equal(env.CACHE_FIX_PROXY_PORT, "9801");
    assert.equal(env.CLAUDE_CONFIG_DIR, path.normalize(root));
    assert.equal(
      env.CACHE_FIX_CA_DIR,
      path.join(root, "cache-fix-ca"),
    );
    assert.equal(env.CACHE_FIX_FORWARD_PROXY, undefined);
  });

  it("sets CACHE_FIX_FORWARD_PROXY=on only in forward mode", () => {
    const fwd = buildProxySpawnEnv({
      port: 8080,
      mode: "forward",
      effectiveConfigRoot: root,
    });
    assert.equal(fwd.CACHE_FIX_FORWARD_PROXY, "on");
    assert.equal(fwd.CACHE_FIX_PROXY_PORT, "8080");
    assert.ok(fwd.CLAUDE_CONFIG_DIR);
    assert.ok(fwd.CACHE_FIX_CA_DIR);

    const rev = buildProxySpawnEnv({
      mode: "reverse",
      effectiveConfigRoot: root,
      baseEnv: { CACHE_FIX_FORWARD_PROXY: "on", PATH: "/usr/bin" },
    });
    assert.equal(rev.CACHE_FIX_FORWARD_PROXY, undefined);
    assert.equal(rev.PATH, "/usr/bin");
  });

  it("honors explicit caDir override", () => {
    const env = buildProxySpawnEnv({
      effectiveConfigRoot: root,
      caDir: "/custom/ca",
    });
    assert.equal(env.CACHE_FIX_CA_DIR, path.normalize("/custom/ca"));
  });

  it("requires effectiveConfigRoot", () => {
    assert.throws(() => buildProxySpawnEnv({ effectiveConfigRoot: "" }));
  });

  it("merges extraEnv (user proxyEnv) into child env", () => {
    const env = buildProxySpawnEnv({
      port: 9801,
      mode: "reverse",
      effectiveConfigRoot: root,
      extraEnv: {
        CACHE_FIX_PROXY_UPSTREAM: "http://127.0.0.1:8080",
        CACHE_FIX_DEBUG: "1",
        HTTPS_PROXY: "http://corp:3128",
      },
    });
    assert.equal(env.CACHE_FIX_PROXY_UPSTREAM, "http://127.0.0.1:8080");
    assert.equal(env.CACHE_FIX_DEBUG, "1");
    assert.equal(env.HTTPS_PROXY, "http://corp:3128");
    assert.equal(env.CACHE_FIX_PROXY_PORT, "9801");
  });

  it("mode wins over extraEnv for CACHE_FIX_FORWARD_PROXY", () => {
    const env = buildProxySpawnEnv({
      mode: "reverse",
      effectiveConfigRoot: root,
      extraEnv: { CACHE_FIX_FORWARD_PROXY: "on" },
    });
    assert.equal(env.CACHE_FIX_FORWARD_PROXY, undefined);
  });

  it("skips empty extraEnv values", () => {
    const env = buildProxySpawnEnv({
      effectiveConfigRoot: root,
      extraEnv: { CACHE_FIX_PROXY_UPSTREAM: "", CACHE_FIX_DEBUG: "1" },
    });
    assert.equal(env.CACHE_FIX_PROXY_UPSTREAM, undefined);
    assert.equal(env.CACHE_FIX_DEBUG, "1");
  });

});
