import { describe, it } from "node:test";
import assert from "node:assert/strict";
import {
  COMPATIBLE_RANGE,
  satisfiesCompatible,
  rankProxyCandidates,
  selectProxy,
} from "../src/proxy-discover.mjs";

describe("COMPATIBLE_RANGE", () => {
  it("is locked to >=4.3.0 <5", () => {
    assert.equal(COMPATIBLE_RANGE, ">=4.3.0 <5");
  });
});

describe("satisfiesCompatible", () => {
  it("accepts 4.3.0 and later 4.x", () => {
    assert.equal(satisfiesCompatible("4.3.0"), true);
    assert.equal(satisfiesCompatible("4.3.1"), true);
    assert.equal(satisfiesCompatible("4.99.0"), true);
    assert.equal(satisfiesCompatible("v4.3.0"), true);
  });

  it("rejects below 4.3.0 and major >=5", () => {
    assert.equal(satisfiesCompatible("4.2.9"), false);
    assert.equal(satisfiesCompatible("4.0.0"), false);
    assert.equal(satisfiesCompatible("3.9.0"), false);
    assert.equal(satisfiesCompatible("5.0.0"), false);
    assert.equal(satisfiesCompatible(""), false);
    assert.equal(satisfiesCompatible(undefined), false);
  });
});

describe("rankProxyCandidates", () => {
  it("orders explicit → path → npm-global → sidecar", () => {
    const ranked = rankProxyCandidates([
      { source: "sidecar", path: "/app/sidecar", version: "4.3.0" },
      { source: "npm-global", path: "/npm/bin", version: "4.3.0" },
      { source: "path", path: "/usr/bin/cache-fix-proxy", version: "4.3.0" },
      { source: "explicit", path: "/opt/proxy", version: "4.3.0" },
    ]);
    assert.deepEqual(
      ranked.map((c) => c.source),
      ["explicit", "path", "npm-global", "sidecar"],
    );
  });
});

describe("selectProxy", () => {
  it("prefers path over sidecar when both compatible", () => {
    const selected = selectProxy([
      { source: "sidecar", path: "/app/sidecar", version: "4.3.0" },
      { source: "path", path: "/usr/bin/cache-fix-proxy", version: "4.4.0" },
    ]);
    assert.equal(selected.source, "path");
    assert.equal(selected.path, "/usr/bin/cache-fix-proxy");
  });

  it("incompatible path prefers sidecar", () => {
    const selected = selectProxy([
      { source: "path", path: "/usr/bin/cache-fix-proxy", version: "4.2.0" },
      { source: "sidecar", path: "/app/sidecar", version: "4.3.0" },
    ]);
    assert.equal(selected.source, "sidecar");
  });

  it("respects compatible:false flag", () => {
    const selected = selectProxy([
      {
        source: "path",
        path: "/usr/bin/cache-fix-proxy",
        version: "4.9.0",
        compatible: false,
      },
      { source: "sidecar", path: "/app/sidecar", version: "4.3.0" },
    ]);
    assert.equal(selected.source, "sidecar");
  });

  it("returns null when nothing compatible", () => {
    const selected = selectProxy([
      { source: "path", path: "/old", version: "3.0.0" },
      { source: "npm-global", path: "/npm", version: "4.1.0" },
    ]);
    assert.equal(selected, null);
  });
});
