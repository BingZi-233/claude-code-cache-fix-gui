import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { parseCacheFixHealth } from "../src/health.mjs";

describe("parseCacheFixHealth", () => {
  it("ok with version", () => {
    const r = parseCacheFixHealth(
      200,
      JSON.stringify({ status: "ok", version: "4.3.0", forward_proxy: false }),
    );
    assert.equal(r.kind, "ok");
    assert.equal(r.version, "4.3.0");
    assert.equal(r.forwardProxy, false);
  });

  it("degraded with 503", () => {
    const r = parseCacheFixHealth(
      503,
      JSON.stringify({
        status: "degraded",
        version: "4.3.1",
        failed_extensions: ["x"],
        hint: "check logs",
      }),
    );
    assert.equal(r.kind, "degraded");
    assert.equal(r.httpStatus, 503);
    assert.deepEqual(r.failed_extensions, ["x"]);
    assert.equal(r.hint, "check logs");
  });

  it("ok with only forward_proxy (no version)", () => {
    const r = parseCacheFixHealth(
      200,
      JSON.stringify({ status: "ok", forward_proxy: true }),
    );
    assert.equal(r.kind, "ok");
    assert.equal(r.forwardProxy, true);
  });

  it("foreign for unknown JSON / missing recognition fields", () => {
    assert.equal(
      parseCacheFixHealth(200, JSON.stringify({ status: "ok" })).kind,
      "foreign",
    );
    assert.equal(
      parseCacheFixHealth(200, JSON.stringify({ healthy: true })).kind,
      "foreign",
    );
    assert.equal(
      parseCacheFixHealth(200, JSON.stringify([1, 2, 3])).kind,
      "foreign",
    );
    assert.equal(parseCacheFixHealth(200, "not-json").kind, "foreign");
  });

  it("unreachable when no status/body", () => {
    assert.equal(parseCacheFixHealth(null, null).kind, "unreachable");
    assert.equal(parseCacheFixHealth(undefined, "").kind, "unreachable");
  });
});
