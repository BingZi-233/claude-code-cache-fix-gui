import { describe, it } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import { fetchHealth } from "../src/controller.mjs";
import { parseCacheFixHealth } from "../src/health.mjs";

describe("fetchHealth + parse integration", () => {
  it("reads real HTTP /health and parses as ok", async () => {
    const server = http.createServer((req, res) => {
      if (req.url === "/health") {
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ status: "ok", version: "4.3.0", forward_proxy: false }));
        return;
      }
      res.writeHead(404);
      res.end();
    });
    await new Promise((r) => server.listen(0, "127.0.0.1", r));
    const { port } = server.address();
    try {
      const { httpStatus, body } = await fetchHealth(port);
      const health = parseCacheFixHealth(httpStatus, body);
      assert.equal(health.kind, "ok");
      assert.equal(health.version, "4.3.0");
      assert.equal(health.forwardProxy, false);
    } finally {
      server.close();
    }
  });

  it("maps 503 degraded body", async () => {
    const server = http.createServer((_req, res) => {
      res.writeHead(503, { "Content-Type": "application/json" });
      res.end(
        JSON.stringify({
          status: "degraded",
          version: "4.3.0",
          forward_proxy: true,
          hint: "reload",
        }),
      );
    });
    await new Promise((r) => server.listen(0, "127.0.0.1", r));
    const { port } = server.address();
    try {
      const { httpStatus, body } = await fetchHealth(port);
      const health = parseCacheFixHealth(httpStatus, body);
      assert.equal(health.kind, "degraded");
      assert.equal(health.forwardProxy, true);
    } finally {
      server.close();
    }
  });
});
