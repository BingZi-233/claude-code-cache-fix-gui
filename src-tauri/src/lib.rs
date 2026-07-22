//! Tauri shell: tray + window hosting the control panel UI.
//!
//! Double-click `cache-fix-gui.exe` is the primary launch path (no .bat required).
//! On startup the shell bootstraps the portable layout next to the exe:
//!   runtime/node.exe + bin/cache-fix-gui.mjs panel + sidecar/
//! then points the webview at `http://127.0.0.1:19801/`.

use std::fs::{self, OpenOptions};
use std::io::Write;
use std::net::TcpStream;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;
use std::thread;
use std::time::Duration;

use tauri::{
    image::Image,
    menu::{Menu, MenuItem},
    tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
    AppHandle, Manager, RunEvent, WindowEvent,
};

const PANEL_PORT: u16 = 19801;
const PANEL_URL: &str = "http://127.0.0.1:19801/";

/// Panel child process (None if we attached to an already-running panel).
static PANEL_CHILD: Mutex<Option<Child>> = Mutex::new(None);

#[tauri::command]
fn greet(name: &str) -> String {
    format!("cache-fix GUI ready, {name}")
}

#[tauri::command]
fn panel_url() -> String {
    PANEL_URL.to_string()
}

/// Directory containing `cache-fix-gui.exe` (portable package root when laid out that way).
fn exe_dir() -> Option<PathBuf> {
    std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(|d| d.to_path_buf()))
}

fn path_is_file(p: &Path) -> bool {
    if p.is_file() {
        return true;
    }
    // Windows: canonicalize can succeed where is_file is flaky on some shares.
    p.canonicalize().map(|c| c.is_file()).unwrap_or(false)
}

fn append_log(root: &Path, line: &str) {
    let path = root.join("cache-fix-gui.log");
    if let Ok(mut f) = OpenOptions::new().create(true).append(true).open(&path) {
        let _ = writeln!(f, "{}", line);
    }
    eprintln!("[cache-fix-gui] {line}");
}

/// Make double-click work without 启动.bat:
/// chdir to portable root + export the same env vars the bat used to set.
fn bootstrap_portable_env() -> PathBuf {
    let root = exe_dir().unwrap_or_else(|| {
        std::env::current_dir().unwrap_or_else(|_| PathBuf::from("."))
    });

    // Explorer / Start Menu / shortcut launches may use an unrelated cwd.
    let _ = std::env::set_current_dir(&root);

    let node = root.join("runtime").join(if cfg!(windows) {
        "node.exe"
    } else {
        "node"
    });
    if path_is_file(&node) {
        std::env::set_var("CACHE_FIX_GUI_NODE", &node);
    }

    std::env::set_var("CACHE_FIX_GUI_ROOT", &root);
    std::env::set_var("CACHE_FIX_GUI_NO_OPEN", "1");
    std::env::set_var("CACHE_FIX_GUI_PORT", PANEL_PORT.to_string());

    let sidecar = root.join("sidecar").join("claude-code-cache-fix");
    if path_is_file(&sidecar.join("proxy").join("server.mjs")) {
        std::env::set_var("CACHE_FIX_GUI_PROXY_ROOT", &sidecar);
        std::env::set_var("CACHE_FIX_SIDECAR", &sidecar);
    }

    append_log(
        &root,
        &format!(
            "bootstrap root={} node={} sidecar={}",
            root.display(),
            path_is_file(&node),
            path_is_file(&sidecar.join("proxy").join("server.mjs"))
        ),
    );

    root
}

fn panel_listening(port: u16) -> bool {
    let addr = match format!("127.0.0.1:{port}").parse() {
        Ok(a) => a,
        Err(_) => return false,
    };
    TcpStream::connect_timeout(&addr, Duration::from_millis(150)).is_ok()
}

fn wait_for_panel(port: u16, attempts: u32) -> bool {
    for _ in 0..attempts {
        if panel_listening(port) {
            return true;
        }
        thread::sleep(Duration::from_millis(100));
    }
    false
}

/// Resolve `bin/cache-fix-gui.mjs` for portable, resource-bundled, or dev layouts.
fn find_panel_script(app: &AppHandle, portable_root: &Path) -> Option<PathBuf> {
    let mut candidates: Vec<PathBuf> = Vec::new();

    // 1) Portable layout next to exe (primary for double-click)
    candidates.push(portable_root.join("bin").join("cache-fix-gui.mjs"));
    candidates.push(portable_root.join("cache-fix-gui.mjs"));

    if let Ok(rd) = app.path().resource_dir() {
        candidates.push(rd.join("bin").join("cache-fix-gui.mjs"));
        candidates.push(rd.join("cache-fix-gui.mjs"));
        // Tauri sometimes nests resources under `_up_` when using `../`
        candidates.push(rd.join("_up_").join("bin").join("cache-fix-gui.mjs"));
    }

    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            // dev: target/debug → ../../bin
            candidates.push(dir.join("../../bin/cache-fix-gui.mjs"));
            candidates.push(dir.join("../../../bin/cache-fix-gui.mjs"));
        }
    }

    // Compile-time path: src-tauri/../bin
    let manifest_related = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("..")
        .join("bin")
        .join("cache-fix-gui.mjs");
    candidates.push(manifest_related);

    if let Ok(cwd) = std::env::current_dir() {
        candidates.push(cwd.join("bin").join("cache-fix-gui.mjs"));
        candidates.push(cwd.join("cache-fix-gui.mjs"));
    }

    for c in candidates {
        if let Ok(canon) = c.canonicalize() {
            if canon.is_file() {
                return Some(canon);
            }
        } else if path_is_file(&c) {
            return Some(c);
        }
    }
    None
}

fn find_node(portable_root: &Path) -> Option<PathBuf> {
    // Explicit override (also set by bootstrap_portable_env when runtime/ exists)
    if let Ok(p) = std::env::var("CACHE_FIX_GUI_NODE") {
        let pb = PathBuf::from(p);
        if path_is_file(&pb) {
            return Some(pb);
        }
    }

    // Portable layout next to this binary
    for rel in [
        "runtime/node.exe",
        "runtime/node",
        "node/node.exe",
        "node.exe",
        "node",
    ] {
        let candidate = portable_root.join(rel);
        if path_is_file(&candidate) {
            return Some(candidate);
        }
    }

    which("node")
}

fn which(bin: &str) -> Option<PathBuf> {
    let path = std::env::var_os("PATH")?;
    for dir in std::env::split_paths(&path) {
        let candidate = dir.join(bin);
        if path_is_file(&candidate) {
            return Some(candidate);
        }
        // Windows: node.exe
        let candidate_exe = dir.join(format!("{bin}.exe"));
        if path_is_file(&candidate_exe) {
            return Some(candidate_exe);
        }
    }
    None
}

/// Returns Ok(true) if we spawned a new child, Ok(false) if already listening.
fn spawn_panel(app: &AppHandle, portable_root: &Path) -> Result<bool, String> {
    if panel_listening(PANEL_PORT) {
        append_log(portable_root, "panel already listening — attaching");
        return Ok(false);
    }

    let node = find_node(portable_root).ok_or_else(|| {
        format!(
            "未找到 Node.js。\n请确认便携目录内存在 runtime\\node.exe：\n  {}\n或安装系统 Node 并加入 PATH。",
            portable_root.join("runtime").join("node.exe").display()
        )
    })?;
    let script = find_panel_script(app, portable_root).ok_or_else(|| {
        format!(
            "未找到 bin\\cache-fix-gui.mjs。\n请确认解压完整便携包，目录结构为：\n  {}\\bin\\cache-fix-gui.mjs",
            portable_root.display()
        )
    })?;

    // Working directory must be package root so panel can resolve ui/ and src/
    let workdir = script
        .parent()
        .and_then(|p| p.parent())
        .map(|p| p.to_path_buf())
        .unwrap_or_else(|| portable_root.to_path_buf());

    let sidecar = workdir.join("sidecar").join("claude-code-cache-fix");
    let sidecar_ok = path_is_file(&sidecar.join("proxy").join("server.mjs"));

    // Capture panel stdout/stderr so silent spawn failures are diagnosable.
    let log_path = portable_root.join("cache-fix-gui-panel.log");

    let mut cmd = Command::new(&node);
    cmd.arg(&script)
        .arg("panel")
        .current_dir(&workdir)
        .env("CACHE_FIX_GUI_NO_OPEN", "1")
        .env("CACHE_FIX_GUI_PORT", PANEL_PORT.to_string())
        .env("CACHE_FIX_GUI_ROOT", &workdir)
        .env("CACHE_FIX_GUI_NODE", &node)
        .stdin(Stdio::null());

    if let Ok(log_file) = fs::File::create(&log_path) {
        match log_file.try_clone() {
            Ok(clone) => {
                cmd.stdout(Stdio::from(clone));
                cmd.stderr(Stdio::from(log_file));
            }
            Err(_) => {
                cmd.stdout(Stdio::null());
                cmd.stderr(Stdio::from(log_file));
            }
        }
    } else {
        cmd.stdout(Stdio::null());
        cmd.stderr(Stdio::null());
    }

    if sidecar_ok {
        cmd.env("CACHE_FIX_GUI_PROXY_ROOT", &sidecar);
        cmd.env("CACHE_FIX_SIDECAR", &sidecar);
    }

    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        // Hide the Node console window (GUI app double-click UX).
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }

    append_log(
        portable_root,
        &format!(
            "spawn panel node={} script={} workdir={} sidecar={}",
            node.display(),
            script.display(),
            workdir.display(),
            sidecar_ok
        ),
    );

    let mut child = cmd.spawn().map_err(|e| {
        format!(
            "启动 panel 失败\n  node: {}\n  script: {}\n  error: {e}",
            node.display(),
            script.display()
        )
    })?;

    // Cold start + AV scan of node.exe can take several seconds on Windows.
    if !wait_for_panel(PANEL_PORT, 100) {
        let _ = child.kill();
        let _ = child.wait();
        let tail = fs::read_to_string(&log_path).unwrap_or_default();
        let tail = tail.chars().rev().take(800).collect::<String>().chars().rev().collect::<String>();
        return Err(format!(
            "已启动 Node panel，但端口 {PANEL_PORT} 未就绪。\n\
             请确认便携目录完整（runtime / bin / ui / sidecar）。\n\
             日志: {}\n{}",
            log_path.display(),
            if tail.trim().is_empty() {
                String::new()
            } else {
                format!("--- panel log ---\n{tail}")
            }
        ));
    }

    if let Ok(mut guard) = PANEL_CHILD.lock() {
        *guard = Some(child);
    }

    append_log(portable_root, "panel ready");
    Ok(true)
}

fn navigate_to_panel(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        // Prefer native navigate when available; fall back to JS.
        // Tauri 2.11 WebviewWindow::navigate takes url::Url.
        match PANEL_URL.parse() {
            Ok(url) => {
                if window.navigate(url).is_err() {
                    let _ = window.eval(&format!("window.location.replace('{PANEL_URL}')"));
                }
            }
            Err(_) => {
                let _ = window.eval(&format!("window.location.replace('{PANEL_URL}')"));
            }
        }
    }
}

fn show_startup_error(app: &AppHandle, msg: &str) {
    if let Some(window) = app.get_webview_window("main") {
        let escaped = msg
            .replace('\\', "\\\\")
            .replace('`', "\\`")
            .replace('$', "\\$")
            .replace('\'', "\\'")
            .replace('\n', "\\n")
            .replace('\r', "");
        // React UI has no #stError — replace document so the user always sees the cause.
        let js = format!(
            "document.documentElement.innerHTML = '<body style=\"font-family:system-ui,sans-serif;padding:24px;background:#0f1115;color:#e8eaed;margin:0\">\
             <h1 style=\"font-size:16px;margin:0 0 12px\">cache-fix 启动失败</h1>\
             <pre style=\"white-space:pre-wrap;font-size:12px;line-height:1.5;background:#1a1d24;padding:12px;border-radius:8px;border:1px solid #2a2f3a\">{escaped}</pre>\
             <p style=\"font-size:12px;color:#9aa0a6;margin-top:16px\">请解压完整便携包后双击 cache-fix-gui.exe。日志见同目录 cache-fix-gui.log</p>\
             </body>';"
        );
        let _ = window.eval(&js);
    }
}

fn stop_panel_child() {
    if let Ok(mut guard) = PANEL_CHILD.lock() {
        if let Some(mut child) = guard.take() {
            let _ = child.kill();
            let _ = child.wait();
        }
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    // Before Tauri starts: chdir + env so double-click equals 启动.bat.
    let portable_root = bootstrap_portable_env();

    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .invoke_handler(tauri::generate_handler![greet, panel_url])
        .setup(move |app| {
            let show_i = MenuItem::with_id(app, "show", "打开控制面板", true, None::<&str>)?;
            let quit_i = MenuItem::with_id(app, "quit", "退出", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&show_i, &quit_i])?;

            let mut tray_builder = TrayIconBuilder::new()
                .menu(&menu)
                .tooltip("cache-fix")
                .on_menu_event(|app, event| match event.id.as_ref() {
                    "show" => {
                        if let Some(w) = app.get_webview_window("main") {
                            let _ = w.show();
                            let _ = w.set_focus();
                        }
                    }
                    "quit" => {
                        app.exit(0);
                    }
                    _ => {}
                })
                .on_tray_icon_event(|tray, event| {
                    if let TrayIconEvent::Click {
                        button: MouseButton::Left,
                        button_state: MouseButtonState::Up,
                        ..
                    } = event
                    {
                        let app = tray.app_handle();
                        if let Some(w) = app.get_webview_window("main") {
                            let _ = w.show();
                            let _ = w.set_focus();
                        }
                    }
                });

            // Tray must have an icon or Windows shows a blank glyph.
            // Prefer the app icon from tauri.conf.json; fall back to embedded RGBA.
            let tray_icon = app.default_window_icon().cloned().unwrap_or_else(|| {
                Image::new_owned(
                    include_bytes!("../icons/32x32.rgba").to_vec(),
                    32,
                    32,
                )
            });
            let _tray = tray_builder.icon(tray_icon).build(app)?;

            if let Some(window) = app.get_webview_window("main") {
                let window_ = window.clone();
                window.on_window_event(move |event| {
                    if let WindowEvent::CloseRequested { api, .. } = event {
                        // Close to tray instead of quitting
                        api.prevent_close();
                        let _ = window_.hide();
                    }
                });
            }

            // Start Node panel API; then point webview at it.
            match spawn_panel(app.handle(), &portable_root) {
                Ok(_) => {
                    navigate_to_panel(app.handle());
                }
                Err(msg) => {
                    append_log(&portable_root, &format!("panel error: {msg}"));
                    show_startup_error(app.handle(), &msg);
                }
            }

            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("error while building tauri application")
        .run(|_app_handle, event| {
            if let RunEvent::Exit = event {
                stop_panel_child();
            }
        });
}
