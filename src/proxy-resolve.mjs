/**
 * Discover a runnable cache-fix-proxy installation (I/O).
 * Ranking uses pure selectProxy from proxy-discover.mjs.
 */
import { access, readFile } from "node:fs/promises";
import { constants as fsConstants } from "node:fs";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { selectProxy, satisfiesCompatible } from "./proxy-discover.mjs";

const execFileAsync = promisify(execFile);
const __dirname = dirname(fileURLToPath(import.meta.url));

/**
 * @param {string} p
 * @returns {Promise<boolean>}
 */
async function exists(p) {
  try {
    await access(p, fsConstants.F_OK);
    return true;
  } catch {
    return false;
  }
}

/**
 * @param {string} cmd
 * @param {string[]} args
 * @returns {Promise<string>}
 */
async function runCapture(cmd, args) {
  try {
    const { stdout } = await execFileAsync(cmd, args, {
      encoding: "utf8",
      timeout: 8000,
      env: process.env,
    });
    return String(stdout || "").trim();
  } catch {
    return "";
  }
}

/**
 * @param {string} packageRoot
 * @returns {Promise<string | undefined>}
 */
async function readPackageVersion(packageRoot) {
  try {
    const raw = await readFile(join(packageRoot, "package.json"), "utf8");
    const j = JSON.parse(raw);
    return typeof j.version === "string" ? j.version : undefined;
  } catch {
    return undefined;
  }
}

/**
 * @param {string} packageRoot
 * @returns {Promise<{ kind: "bin" | "server", command: string, args: string[], packageRoot: string } | null>}
 */
async function packageLaunch(packageRoot) {
  const server = join(packageRoot, "proxy", "server.mjs");
  const bin = join(packageRoot, "bin", "claude-via-proxy.mjs");
  if (await exists(server)) {
    return {
      kind: "server",
      command: process.execPath,
      args: [server],
      packageRoot,
    };
  }
  if (await exists(bin)) {
    return {
      kind: "bin",
      command: process.execPath,
      args: [bin, "server"],
      packageRoot,
    };
  }
  return null;
}

/**
 * Build candidate list and select best compatible proxy launch.
 *
 * @param {{
 *   explicitPath?: string,
 *   sidecarRoot?: string,
 *   env?: Record<string, string | undefined>,
 * }} [opts]
 * @returns {Promise<{
 *   source: string,
 *   path: string,
 *   version?: string,
 *   command: string,
 *   args: string[],
 *   packageRoot?: string,
 * } | null>}
 */
/**
 * Candidate sidecar roots (portable layout + dev).
 * @param {string | undefined} preferred
 * @param {Record<string, string | undefined>} env
 * @returns {string[]}
 */
function sidecarRoots(preferred, env) {
  const roots = [];
  if (preferred) roots.push(preferred);
  if (env.CACHE_FIX_SIDECAR) roots.push(resolve(env.CACHE_FIX_SIDECAR));
  // GUI package root / portable root: <gui>/sidecar/claude-code-cache-fix
  roots.push(join(__dirname, "..", "sidecar", "claude-code-cache-fix"));
  // cwd (when launched from portable folder)
  if (env.CACHE_FIX_GUI_ROOT) {
    roots.push(join(resolve(env.CACHE_FIX_GUI_ROOT), "sidecar", "claude-code-cache-fix"));
  }
  try {
    roots.push(join(process.cwd(), "sidecar", "claude-code-cache-fix"));
  } catch {
    /* ignore */
  }
  // de-dupe
  return [...new Set(roots.map((r) => resolve(r)))];
}

/**
 * Push a package-root candidate if launchable.
 * @param {typeof candidates} candidates
 * @param {{ source: string, packageRoot: string, forceCompatible?: boolean }} spec
 */
async function pushPackageCandidate(candidates, { source, packageRoot, forceCompatible }) {
  const launch = await packageLaunch(packageRoot);
  if (!launch) return;
  const version = await readPackageVersion(packageRoot);
  candidates.push({
    source,
    path: packageRoot,
    version,
    compatible:
      forceCompatible || source === "sidecar"
        ? true
        : version
          ? satisfiesCompatible(version)
          : source === "explicit",
    command: launch.command,
    args: launch.args,
    packageRoot: launch.packageRoot,
  });
}

export async function resolveProxyLaunch(opts = {}) {
  const {
    explicitPath,
    sidecarRoot,
    env = process.env,
  } = opts;

  /** @type {Array<{ source: string, path: string, version?: string, compatible?: boolean, command?: string, args?: string[], packageRoot?: string }>} */
  const candidates = [];

  // 1) Explicit path — file or package root
  if (typeof explicitPath === "string" && explicitPath.trim() !== "") {
    const p = resolve(explicitPath);
    if (await exists(p)) {
      let launch = await packageLaunch(p);
      if (!launch) {
        // treat as direct script or binary
        const version = await readPackageVersion(dirname(p));
        candidates.push({
          source: "explicit",
          path: p,
          version,
          compatible: version ? satisfiesCompatible(version) : true,
          command: p.endsWith(".mjs") || p.endsWith(".js") ? process.execPath : p,
          args: p.endsWith(".mjs") || p.endsWith(".js") ? [p] : [],
          packageRoot: dirname(p),
        });
      } else {
        const version = await readPackageVersion(p);
        candidates.push({
          source: "explicit",
          path: p,
          version,
          compatible: version ? satisfiesCompatible(version) : true,
          command: launch.command,
          args: launch.args,
          packageRoot: launch.packageRoot,
        });
      }
    }
  }

  // 1b) Env override for package root (portable / advanced)
  if (env.CACHE_FIX_GUI_PROXY_ROOT) {
    const root = resolve(env.CACHE_FIX_GUI_PROXY_ROOT);
    if (await exists(join(root, "proxy", "server.mjs"))) {
      await pushPackageCandidate(candidates, { source: "explicit", packageRoot: root });
    }
  }

  // 2) cache-fix-proxy on PATH
  const whichCmd = process.platform === "win32" ? "where" : "which";
  const whichOut = await runCapture(whichCmd, ["cache-fix-proxy"]);
  const pathHit = whichOut.split(/\r?\n/).map((s) => s.trim()).find(Boolean);
  if (pathHit && (await exists(pathHit))) {
    // Prefer adjacent package if resolvable via npm
    const npmRoot = await runCapture("npm", ["root", "-g"]);
    let version;
    let packageRoot;
    if (npmRoot) {
      packageRoot = join(npmRoot, "claude-code-cache-fix");
      version = await readPackageVersion(packageRoot);
    }
    candidates.push({
      source: "path",
      path: pathHit,
      version,
      compatible: version ? satisfiesCompatible(version) : true,
      command: pathHit,
      args: ["server"],
      packageRoot,
    });
  }

  // 3) npm global package
  const npmRoot = await runCapture("npm", ["root", "-g"]);
  if (npmRoot) {
    const packageRoot = join(npmRoot, "claude-code-cache-fix");
    const launch = await packageLaunch(packageRoot);
    if (launch) {
      const version = await readPackageVersion(packageRoot);
      candidates.push({
        source: "npm-global",
        path: packageRoot,
        version,
        compatible: version ? satisfiesCompatible(version) : false,
        command: launch.command,
        args: launch.args,
        packageRoot,
      });
    }
  }

  // 3b) sibling monorepo checkout (dev: ../claude-code-cache-fix next to GUI repo)
  const sibling = join(__dirname, "..", "..", "claude-code-cache-fix");
  if (await exists(join(sibling, "proxy", "server.mjs"))) {
    await pushPackageCandidate(candidates, {
      source: "npm-global", // between path and sidecar
      packageRoot: sibling,
    });
  }

  // 4) Embedded / portable sidecars
  for (const root of sidecarRoots(sidecarRoot, env)) {
    if (await exists(join(root, "proxy", "server.mjs"))) {
      await pushPackageCandidate(candidates, {
        source: "sidecar",
        packageRoot: root,
        forceCompatible: true,
      });
    }
  }

  const selected = selectProxy(candidates);
  if (!selected) return null;

  // Re-find full launch metadata from candidates (selectProxy strips extras if we only ranked pure fields)
  const full = candidates.find(
    (c) => c.source === selected.source && c.path === selected.path,
  );
  if (!full || !full.command) return null;

  return {
    source: full.source,
    path: full.path,
    version: full.version,
    command: full.command,
    args: full.args || [],
    packageRoot: full.packageRoot,
  };
}
