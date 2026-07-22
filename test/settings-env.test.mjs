import { describe, it } from "node:test";
import assert from "node:assert/strict";
import path from "node:path";
import {
  validatePort,
  computeExpectedEnv,
  mergeNoProxy,
  stripLocalhostNoProxy,
  applyClaudeEnv,
  removeClaudeEnv,
  DEFAULT_PORT,
} from "../src/settings-env.mjs";

describe("validatePort", () => {
  it("accepts integer 1..65535 as string", () => {
    assert.equal(validatePort(1), "1");
    assert.equal(validatePort(9801), "9801");
    assert.equal(validatePort(65535), "65535");
    assert.equal(validatePort("8080"), "8080");
  });

  it("rejects out of range / non-decimal", () => {
    assert.throws(() => validatePort(0));
    assert.throws(() => validatePort(65536));
    assert.throws(() => validatePort(-1));
    assert.throws(() => validatePort("abc"));
    assert.throws(() => validatePort("80.5"));
    assert.throws(() => validatePort(null));
  });
});

describe("mergeNoProxy / stripLocalhostNoProxy", () => {
  it("mergeNoProxy appends missing localhost hosts", () => {
    assert.equal(
      mergeNoProxy(undefined),
      "127.0.0.1,localhost,::1",
    );
    assert.equal(
      mergeNoProxy("corp.example"),
      "corp.example,127.0.0.1,localhost,::1",
    );
    // de-dupe exact match; do not re-append present hosts
    assert.equal(
      mergeNoProxy("127.0.0.1,corp.example"),
      "127.0.0.1,corp.example,localhost,::1",
    );
  });

  it("stripLocalhostNoProxy removes only three hosts", () => {
    assert.equal(
      stripLocalhostNoProxy("corp.example,127.0.0.1,localhost,::1"),
      "corp.example",
    );
    assert.equal(stripLocalhostNoProxy("127.0.0.1,localhost,::1"), undefined);
    assert.equal(stripLocalhostNoProxy(undefined), undefined);
  });
});

describe("computeExpectedEnv", () => {
  it("reverse sets ANTHROPIC_BASE_URL with default port 9801", () => {
    assert.deepEqual(computeExpectedEnv({ mode: "reverse" }), {
      ANTHROPIC_BASE_URL: `http://127.0.0.1:${DEFAULT_PORT}`,
    });
  });

  it("forward sets dual-case proxy + CA + NO_PROXY", () => {
    const ca = "/home/u/.claude/cache-fix-ca/ca.pem";
    const env = computeExpectedEnv({
      mode: "forward",
      port: 9801,
      caPemPath: ca,
      existingNoProxy: "corp.example",
    });
    assert.equal(env.HTTPS_PROXY, "http://127.0.0.1:9801");
    assert.equal(env.https_proxy, "http://127.0.0.1:9801");
    assert.equal(env.NODE_EXTRA_CA_CERTS, path.normalize(ca));
    assert.equal(env.NO_PROXY, "corp.example,127.0.0.1,localhost,::1");
    assert.equal(env.no_proxy, env.NO_PROXY);
  });

  it("forward requires caPemPath", () => {
    assert.throws(() => computeExpectedEnv({ mode: "forward", port: 1 }));
  });
});

describe("applyClaudeEnv reverse", () => {
  it("sets ANTHROPIC_BASE_URL and strips matching forward keys", () => {
    const ca = path.normalize("/tmp/ca.pem");
    const settings = {
      env: {
        HTTPS_PROXY: "http://127.0.0.1:9801",
        https_proxy: "http://127.0.0.1:9801",
        NODE_EXTRA_CA_CERTS: ca,
        NO_PROXY: "corp.example,127.0.0.1,localhost,::1",
        no_proxy: "corp.example,127.0.0.1,localhost,::1",
        OTHER: "keep-me",
      },
      model: "claude",
    };
    const { nextSettings, expectedEnv } = applyClaudeEnv(
      settings,
      { mode: "reverse", port: 9801, caPemPath: ca },
    );
    assert.equal(
      nextSettings.env.ANTHROPIC_BASE_URL,
      "http://127.0.0.1:9801",
    );
    assert.equal(expectedEnv.ANTHROPIC_BASE_URL, "http://127.0.0.1:9801");
    assert.equal(nextSettings.env.HTTPS_PROXY, undefined);
    assert.equal(nextSettings.env.https_proxy, undefined);
    assert.equal(nextSettings.env.NODE_EXTRA_CA_CERTS, undefined);
    // corp survives strip of localhost hosts
    assert.equal(nextSettings.env.NO_PROXY, "corp.example");
    assert.equal(nextSettings.env.OTHER, "keep-me");
    assert.equal(nextSettings.model, "claude");
  });
});

describe("applyClaudeEnv forward", () => {
  it("sets dual-case proxy+CA, snapshots+removes ANTHROPIC_BASE_URL, merges NO_PROXY", () => {
    const ca = "/home/u/.claude/cache-fix-ca/ca.pem";
    const settings = {
      env: {
        ANTHROPIC_BASE_URL: "https://api.anthropic.com",
        NO_PROXY: "corp.example",
      },
    };
    const { nextSettings, expectedEnv, anthropicBaseUrlBackup } = applyClaudeEnv(
      settings,
      { mode: "forward", port: 9801, caPemPath: ca },
    );
    assert.equal(anthropicBaseUrlBackup, "https://api.anthropic.com");
    assert.equal(nextSettings.env.ANTHROPIC_BASE_URL, undefined);
    assert.equal(nextSettings.env.HTTPS_PROXY, "http://127.0.0.1:9801");
    assert.equal(nextSettings.env.https_proxy, "http://127.0.0.1:9801");
    assert.equal(nextSettings.env.NODE_EXTRA_CA_CERTS, path.normalize(ca));
    assert.equal(
      nextSettings.env.NO_PROXY,
      "corp.example,127.0.0.1,localhost,::1",
    );
    assert.equal(nextSettings.env.no_proxy, nextSettings.env.NO_PROXY);
    assert.deepEqual(expectedEnv.HTTPS_PROXY, nextSettings.env.HTTPS_PROXY);
  });
});

describe("removeClaudeEnv", () => {
  it("only removes exact-match values; restores anthropicBaseUrlBackup", () => {
    const expectedEnv = {
      ANTHROPIC_BASE_URL: "http://127.0.0.1:9801",
    };
    const settings = {
      env: {
        ANTHROPIC_BASE_URL: "http://127.0.0.1:9801",
        UNRELATED: "x",
      },
    };
    const { nextSettings, skipped, anthropicBaseUrlBackup } = removeClaudeEnv(
      settings,
      expectedEnv,
      { anthropicBaseUrlBackup: "https://api.anthropic.com" },
    );
    assert.deepEqual(skipped, []);
    // backup restored because key was removed then absent → restore
    assert.equal(nextSettings.env.ANTHROPIC_BASE_URL, "https://api.anthropic.com");
    assert.equal(anthropicBaseUrlBackup, null);
    assert.equal(nextSettings.env.UNRELATED, "x");
  });

  it("skips mismatched keys", () => {
    const expectedEnv = {
      ANTHROPIC_BASE_URL: "http://127.0.0.1:9801",
    };
    const settings = {
      env: {
        ANTHROPIC_BASE_URL: "http://127.0.0.1:9999",
      },
    };
    const { nextSettings, skipped } = removeClaudeEnv(settings, expectedEnv);
    assert.deepEqual(skipped, ["ANTHROPIC_BASE_URL"]);
    assert.equal(nextSettings.env.ANTHROPIC_BASE_URL, "http://127.0.0.1:9999");
  });

  it("does not restore backup when user already set ANTHROPIC_BASE_URL", () => {
    const expectedEnv = {
      HTTPS_PROXY: "http://127.0.0.1:9801",
      https_proxy: "http://127.0.0.1:9801",
      NODE_EXTRA_CA_CERTS: "/tmp/ca.pem",
      NO_PROXY: "127.0.0.1,localhost,::1",
      no_proxy: "127.0.0.1,localhost,::1",
    };
    const settings = {
      env: {
        HTTPS_PROXY: "http://127.0.0.1:9801",
        https_proxy: "http://127.0.0.1:9801",
        NODE_EXTRA_CA_CERTS: "/tmp/ca.pem",
        NO_PROXY: "127.0.0.1,localhost,::1",
        no_proxy: "127.0.0.1,localhost,::1",
        ANTHROPIC_BASE_URL: "https://user-set.example",
      },
    };
    const { nextSettings, anthropicBaseUrlBackup } = removeClaudeEnv(
      settings,
      expectedEnv,
      { anthropicBaseUrlBackup: "https://api.anthropic.com" },
    );
    assert.equal(nextSettings.env.ANTHROPIC_BASE_URL, "https://user-set.example");
    // backup not consumed
    assert.equal(anthropicBaseUrlBackup, "https://api.anthropic.com");
  });
});

describe("NO_PROXY corp survives apply→unwire", () => {
  it("corp.example remains after forward apply then remove", () => {
    const ca = "/tmp/ca.pem";
    const { nextSettings: wired, expectedEnv, anthropicBaseUrlBackup } =
      applyClaudeEnv(
        { env: { NO_PROXY: "corp.example,.internal" } },
        { mode: "forward", port: 9801, caPemPath: ca },
      );
    assert.match(wired.env.NO_PROXY, /corp\.example/);
    assert.match(wired.env.NO_PROXY, /127\.0\.0\.1/);

    const { nextSettings: unwired } = removeClaudeEnv(
      wired,
      expectedEnv,
      { anthropicBaseUrlBackup },
    );
    assert.equal(unwired.env.NO_PROXY, "corp.example,.internal");
    assert.equal(unwired.env.no_proxy, "corp.example,.internal");
    assert.equal(unwired.env.HTTPS_PROXY, undefined);
    assert.equal(unwired.env.NODE_EXTRA_CA_CERTS, undefined);
  });
});

describe("fail closed on bad settings shape", () => {
  it("throws when settings is not object", () => {
    assert.throws(() => applyClaudeEnv(null, { mode: "reverse" }));
    assert.throws(() => applyClaudeEnv("x", { mode: "reverse" }));
    assert.throws(() => applyClaudeEnv([], { mode: "reverse" }));
  });

  it("throws when settings.env is not object", () => {
    assert.throws(() =>
      applyClaudeEnv({ env: "bad" }, { mode: "reverse" }),
    );
    assert.throws(() =>
      removeClaudeEnv({ env: ["x"] }, { ANTHROPIC_BASE_URL: "x" }),
    );
  });
});
