# claude-code-cache-fix-gui

[English](README.md)

为 [claude-code-cache-fix](https://github.com/cnighswonger/claude-code-cache-fix) 提供的桌面控制面板。

**仓库:** [BingZi-233/claude-code-cache-fix-gui](https://github.com/BingZi-233/claude-code-cache-fix-gui)  
**技术栈:** Kotlin Multiplatform + Compose Desktop  
**许可证:** MIT

## 这是什么？

面向 [claude-code-cache-fix](https://github.com/cnighswonger/claude-code-cache-fix) 的 **原生桌面控制面板**：一键启停本地代理，并把 **Claude Code 全局配置** 写成走该代理——不用记一堆命令行。

**不会**替你启动 Claude CLI。Claude 仍由你自己打开；本应用只负责代理进程与 `settings.json` 的 env 写入/恢复。

## 功能

### 代理生命周期

- **启动 / 停止 / 重启** cache-fix 代理进程
- 实时 **状态与健康检查**（运行中、已停止、错误信息）
- **日志尾部** 便于排查
- 未找到代理包时，可尝试通过 **npm 自动安装** `claude-code-cache-fix`

### 接入 Claude Code

- **写入 / 恢复（Wire / Unwire）** 全局配置：`{CLAUDE_CONFIG_DIR||~/.claude}/settings.json` 中的 `env`
- **反向代理模式（Reverse）：** 设置 `ANTHROPIC_BASE_URL=http://127.0.0.1:<port>`
- **正向代理模式（Forward）：** 设置 `HTTPS_PROXY`、`NODE_EXTRA_CA_CERTS`，合并本机地址到 `NO_PROXY`，并快照原先的 `ANTHROPIC_BASE_URL`
- 尊重环境变量 **`CLAUDE_CONFIG_DIR`**
- 启动时可自动写入配置；停止/退出时可按控制器逻辑恢复原先 env

### 代理配置界面

- 端口、绑定地址、反向 / 正向 **模式**
- **上游（Upstream）** 配置（置顶）
- 企业网络相关项、扩展项，以及 **高级 KEY=value** 环境变量编辑
- 保存配置，并在应用前 **预览将要写入的 env**
- Debug 开关等代理相关选项

### 自动发现

按以下顺序解析代理可执行文件 / 安装包：

1. 应用内保存的显式路径
2. `PATH` 上的 `cache-fix-proxy`
3. npm 全局安装 / 旁路源码检出
4. 可选内嵌 `sidecar/claude-code-cache-fix`

兼容上游版本：**`>=4.3.0 <5`**。

### 桌面体验

- **Compose Desktop** 控制台（控制页 + 设置页）
- **系统托盘** — 关闭窗口可隐藏到托盘，从托盘恢复
- 可配置：关闭到托盘、启动时直接进托盘
- Windows 单文件 **GUI 子系统** exe（无黑色 CMD 窗口）
- 可选命令行：`status` / `start` / `stop` / `wire` / `unwire` / `discover`

## 下载

请到 [Releases](https://github.com/BingZi-233/claude-code-cache-fix-gui/releases) 获取：

| 产物 | 平台 |
|------|------|
| `cache-fix-gui-kmp.exe` | Windows 单文件 PE（推荐） |
| `*.msi` | Windows 安装包（含开始菜单项 + 桌面快捷方式） |
| `*-arm64.dmg` | macOS **Apple Silicon** |

macOS **Intel（x64）** 不再发布；Intel Mac 请从源码或 fat jar 运行。

安装包 **未签名**，系统可能提示“未知开发者”。  
Windows PE 需要本机 **Java 17+**（`PATH` / `JAVA_HOME`，或旁路 runtime）。

## 快速开始

1. 安装兼容的 [claude-code-cache-fix](https://github.com/cnighswonger/claude-code-cache-fix)（也可让应用尝试 npm 安装）。
2. 从 Releases 下载对应系统的构建产物。
3. 打开应用 → 配置端口 / 模式 / 上游 → **启动**。
4. 使用 **写入配置**（或启动时自动写入），让 Claude Code 使用代理 env。
5. 照常启动 Claude Code。

## 从源码构建

```bash
# JDK 17+
./gradlew :shared:allTests :desktop:test :desktop:fatJar
./gradlew :desktop:run

# Windows 单文件 PE（Linux/CI 需 MinGW）
./scripts-kmp/package-windows.sh
```

CLI 示例：

```bash
java -jar desktop/build/libs/cache-fix-gui-kmp-all.jar status
java -jar desktop/build/libs/cache-fix-gui-kmp-all.jar start
java -jar desktop/build/libs/cache-fix-gui-kmp-all.jar wire
```

CI 通过 [`.github/workflows/build.yml`](.github/workflows/build.yml) 构建 Windows PE/MSI 与 macOS arm64 DMG。

## 项目结构

```
shared/          领域逻辑（配置、wire、健康检查、发现）
desktop/         Compose Desktop UI + CLI
scripts-kmp/     Windows PE 打包脚本
.github/         CI 工作流
docs/            设计笔记（历史）
```
