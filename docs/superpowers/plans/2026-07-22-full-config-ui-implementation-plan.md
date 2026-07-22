# claude-code-cache-fix 完整配置界面实现计划（方案 B）

**方案 B 确认**：每个配置项使用专用界面控件（核心字段 + 分组表单 + 高级键值表）。

## 全局约束

- 遵循现有代码风格（Node.js ESM + Tauri）
- 核心字段（port、mode、upstream 等）必须保留原有行为
- 持久化到 `~/.cache-fix-gui/state.json`
- 启动代理时注入 `buildProxySpawnEnv`
- Upstream 必须放在最显眼位置

## 文件清单

**新增/修改文件：**

- `src/proxy-env-catalog.mjs` —— 所有配置项元数据（可选，记录已知变量）
- `src/controller.mjs` —— `saveAppState`、`getStatus`、`previewWireEnv` 支持 proxyEnv
- `src/spawn-env.mjs` —— `buildProxySpawnEnv` 支持用户配置
- `src/panel-server.mjs` —— 新增 `/api/config` 处理
- `ui/index.html` —— 重构表单（分组 + 手写控件）
- `ui/app.js` —— 更新表单事件处理 + 预览
- `test/spawn-env.test.mjs` —— 新增测试
- `test/settings-io.test.mjs` —— 新增 proxyEnv 测试

## 任务分解（TDD 风格）

### Task 1: 新增配置元数据（catalog）

**Files:**
- Create: `src/proxy-env-catalog.mjs`

**Interfaces:**
- Consumes: 无
- Produces: `getConfigCatalog()`

```js
// 示例
export function getConfigCatalog() {
  return {
    CACHE_FIX_PROXY_UPSTREAM: {
      group: "core",
      type: "url",
      label: "Upstream",
      default: "https://api.anthropic.com",
      help: "上游地址（支持中继）"
    },
    // ... 其余约 80 项
  };
}
```

### Task 2: 更新 controller 支持 proxyEnv

**Files:**
- Modify: `src/controller.mjs:180-300`

```js
export function saveAppState(patch) {
  // 已有逻辑 + 支持 proxyEnv
  const state = { ...loadAppState(), ...patch };
  if (patch.proxyEnv) state.proxyEnv = patch.proxyEnv;
  // ...
}

export async function previewWireEnv() {
  // 增加 proxyEnv 预览
}
```

### Task 3: 更新 spawn-env 支持用户配置

**Files:**
- Modify: `src/spawn-env.mjs:30-80`

```js
export function buildProxySpawnEnv({
  port, mode, effectiveConfigRoot, caDir, baseEnv, extraEnv = {}
}) {
  const env = { ...baseEnv };
  // 核心注入
  env.CACHE_FIX_PROXY_PORT = validatePort(port);
  env.CLAUDE_CONFIG_DIR = path.normalize(effectiveConfigRoot);
  env.CACHE_FIX_CA_DIR = resolvedCaDir;

  // 用户配置注入（优先级更高）
  for (const [k, v] of Object.entries(extraEnv)) {
    if (v !== undefined) env[k] = v;
  }

  if (mode === "forward") env.CACHE_FIX_FORWARD_PROXY = "on";
  else delete env.CACHE_FIX_FORWARD_PROXY;

  return env;
}
```

### Task 4: 重构 UI 表单（手写控件）

**Files:**
- Modify: `ui/index.html:80-300`
- Modify: `ui/app.js:100-300`

**结构：**

```html
<section class="card">
  <h2>基础配置</h2>
  <div class="form-grid">
    <label>Upstream
      <input id="upstream" type="url" value="..." />
    </label>
    <label>端口
      <input id="port" type="number" />
    </label>
    <!-- 其他核心字段 -->
  </div>
</section>

<!-- 企业网络分组 -->
<!-- Forward 增强分组 -->
<!-- 高级配置 -->
```

表单事件：
- `oninput` 更新 `appState.proxyEnv[key] = value`
- 每 400ms 自动保存 `POST /api/config`

### Task 5: 更新 API 和状态

**Files:**
- Modify: `src/panel-server.mjs:130-160`
- Modify: `src/controller.mjs:250-320`

添加：
- `POST /api/config` 支持 `proxyEnv` 和核心字段
- `GET /api/config` 返回当前配置 + catalog

### Task 6: 测试与验证

- 单元测试 `buildProxySpawnEnv`（用户配置注入）
- 端到端测试表单保存/加载
- 确保重启后生效

## 交付物

- `docs/superpowers/specs/2026-07-22-claude-cache-fix-full-config-ui.md`
- `docs/superpowers/plans/2026-07-22-full-config-ui-implementation-plan.md`
- 功能完成：GUI 可完整配置所有 `CACHE_FIX_*`（核心 + 高级键值表）

---

计划已写入。请问是否同意执行？（可选：我可以直接用 subagent 执行每个任务，或你确认后我批量执行）