#!/usr/bin/env node
/**
 * CLI + control panel launcher for cache-fix GUI.
 *
 * Usage:
 *   cache-fix-gui                 Start panel server (default)
 *   cache-fix-gui panel           Same
 *   cache-fix-gui start           Start proxy
 *   cache-fix-gui stop            Stop managed proxy
 *   cache-fix-gui status          Print status JSON
 *   cache-fix-gui wire            Write Claude settings env
 *   cache-fix-gui unwire          Remove Claude settings env
 *   cache-fix-gui discover        Resolve proxy binary
 */
import { spawn } from "node:child_process";
import { platform } from "node:os";
import * as controller from "../src/controller.mjs";
import { startPanelServer } from "../src/panel-server.mjs";

const args = process.argv.slice(2);
const cmd = args[0] || "panel";

function print(obj) {
  process.stdout.write(`${JSON.stringify(obj, null, 2)}\n`);
}

function openBrowser(url) {
  const plat = platform();
  let command;
  let cargs;
  if (plat === "darwin") {
    command = "open";
    cargs = [url];
  } else if (plat === "win32") {
    command = "cmd";
    cargs = ["/c", "start", "", url];
  } else {
    command = "xdg-open";
    cargs = [url];
  }
  try {
    const p = spawn(command, cargs, { stdio: "ignore", detached: true });
    p.unref();
  } catch {
    /* optional */
  }
}

async function main() {
  switch (cmd) {
    case "help":
    case "-h":
    case "--help": {
      process.stdout.write(
        "Usage: cache-fix-gui [panel|start|stop|restart|status|wire|unwire|discover|help]\n" +
          "\n" +
          "  panel (default)  Open local control panel on 127.0.0.1:19801\n" +
          "  start            Start cache-fix proxy\n" +
          "  stop             Stop GUI-managed proxy (detach if attached)\n" +
          "  restart          Restart proxy\n" +
          "  status           Print status JSON\n" +
          "  wire             Write Claude global settings.json env\n" +
          "  unwire           Remove GUI-managed env from settings.json\n" +
          "  discover         Resolve proxy binary (PATH/npm/sidecar)\n" +
          "\n" +
          "Env:\n" +
          "  CLAUDE_CONFIG_DIR   Claude config root (honored)\n" +
          "  CACHE_FIX_GUI_PORT  Panel listen port (default 19801)\n" +
          "  CACHE_FIX_GUI_NO_OPEN=1  Do not open browser\n",
      );
      return 0;
    }
    case "status": {
      print(await controller.getStatus());
      return 0;
    }
    case "discover": {
      print({ launch: await controller.discover(), status: await controller.getStatus() });
      return 0;
    }
    case "start": {
      // optional: --port N --mode reverse|forward
      const patch = {};
      for (let i = 1; i < args.length; i++) {
        if (args[i] === "--port" && args[i + 1]) patch.port = Number(args[++i]);
        else if (args[i] === "--mode" && args[i + 1]) patch.mode = args[++i];
      }
      if (Object.keys(patch).length) controller.saveAppState(patch);
      print(await controller.startProxy());
      return 0;
    }
    case "stop": {
      print(await controller.stopProxy());
      return 0;
    }
    case "restart": {
      print(await controller.restartProxy());
      return 0;
    }
    case "wire": {
      print(await controller.wireClaude());
      return 0;
    }
    case "unwire": {
      print(await controller.unwireClaude());
      return 0;
    }
    case "panel":
    case "gui":
    case "serve": {
      const port = Number(process.env.CACHE_FIX_GUI_PORT || 19801);
      const { url, server } = await startPanelServer({ port });
      process.stdout.write(`cache-fix GUI panel: ${url}\n`);
      process.stdout.write("Press Ctrl+C to stop the panel (proxy quit policy applies on shutdown API).\n");
      if (process.env.CACHE_FIX_GUI_NO_OPEN !== "1") openBrowser(url);

      const shutdown = async () => {
        process.stdout.write("\nShutting down panel…\n");
        try {
          await controller.shutdown();
        } catch {
          /* ignore */
        }
        server.close();
        process.exit(0);
      };
      process.on("SIGINT", shutdown);
      process.on("SIGTERM", shutdown);
      return new Promise(() => {
        /* keep alive */
      });
    }
    default:
      process.stderr.write(`Unknown command: ${cmd}\n`);
      return 1;
  }
}

const code = await main();
if (typeof code === "number") process.exit(code);
