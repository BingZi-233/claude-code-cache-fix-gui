# claude-code-cache-fix 完整配置界面设计

**状态**：方案 B 确认（每个配置项用专用界面控件）

**目标**：GUI 覆盖上游全部主要 `CACHE_FIX_*` 配置项，并把 **Upstream** 放在最显眼位置（大多数用户会改）。

**技术路线（B 方案）**：
- 每个重要配置项都有**明确 UI 控件**（input、select、checkbox、number 等）
- 核心字段（port、upstream、mode、bind、timeout、debug 等）置顶
- 企业网络、Forward 增强、常用扩展、其他高级配置分分组
- 剩余极少用配置放在「高级」分组，用键值对表（text inputs + JSON editor）
- 不依赖 catalog 自动渲染作为主路径（避免 UI 代码爆炸）

## 1. 配置分组与控件

### 1.1 基础（默认展开，核心必配）
| 配置项 | 类型 | 控件 | 备注 |
|--------|------|------|------|
| 端口 | 端口号 | number 输入框 | 默认 9801 |
| Upstream | URL | 大输入框 | **最显眼**，默认 `https://api.anthropic.com`，支持中继地址 |
| 模式 | 枚举 | select | reverse / forward |
| 绑定地址 | 地址 | input | 默认 127.0.0.1 |
| 超时时间 | 毫秒 | number 输入框 | 默认 600000 |
| 调试日志 | 布尔 | checkbox | 默认关闭 |
| CA 文件路径 | 文件路径 | input | 企业 CA |

### 1.2 企业网络
| 配置项 | 类型 | 控件 |
|--------|------|------|
| HTTPS_PROXY / HTTP_PROXY | URL | input |
| NO_PROXY | 逗号分隔列表 | input |
| CACHE_FIX_PROXY_CA_FILE | 文件路径 | input |
| CACHE_FIX_PROXY_REJECT_UNAUTHORIZED | 布尔 | checkbox |

### 1.3 Forward 增强
| 配置项 | 类型 | 控件 |
|--------|------|------|
| CACHE_FIX_DOWNLOAD_REWRITE | 布尔 | checkbox |
| CACHE_FIX_OAUTH_REFRESH | 布尔 | checkbox |
| CACHE_FIX_CA_DIR | 文件路径 | input |

### 1.4 常用扩展开关（按功能分组）
- Thinking：`THINKING_SANITIZE`、`THINKING_DISPLAY`、`THINKING_RISK_*`
- Image：`IMAGE_GUARD`、`IMAGE_PRESERVE_DETAIL`、`IMAGE_MAX_DIM`
- Session Mirror：`SESSION_MIRROR`、`SESSION_MIRROR_*` 系列
- Bootstrap、Quota、Usage Log 等

### 1.5 高级 / 其余配置
- 用 **键值对表**（text inputs + Add / Remove 按钮）
- 或者 JSON 文本框（进阶用户）

## 2. 状态管理

- `~/.cache-fix-gui/state.json` 新增：
  ```json
  "proxyEnv": {
    "CACHE_FIX_PROXY_UPSTREAM": "https://...",
    "CACHE_FIX_PROXY_PORT": "9801",
    ...
  }
  ```
- 核心字段单独存（port、mode、configDirOverride、upstream、bind、timeout、debug、caFile 等）

## 3. API 扩展

- `GET /api/config`：返回当前 proxyEnv + catalog 元数据（用于表单初始化）
- `POST /api/config`：支持 `proxyEnv`、核心字段、`upstream` 等

## 4. spawn-env 更新

```js
buildProxySpawnEnv({
  port, mode, effectiveConfigRoot, caDir, baseEnv, extraEnv: user.proxyEnv
})
```

## 5. 实现顺序

1. 新增 `src/proxy-env-catalog.mjs`（可选，记录所有已知配置）
2. 更新 `controller.mjs`：`saveAppState`、`getStatus` 支持 proxyEnv
3. 更新 `spawn-env.mjs`：注入用户设置
4. 修改 `ui/index.html` + `app.js`：重构表单（手写控件）
5. 更新 `panel-server.mjs`：支持新 API
6. 添加单元测试

---

**设计已写好，请确认。**