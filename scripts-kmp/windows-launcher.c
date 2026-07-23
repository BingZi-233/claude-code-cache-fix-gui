/*
 * Single-file Windows PE for cache-fix-gui (KMP + Compose).
 *
 * Layout of the final executable:
 *   [ PE stub ][ fat JAR bytes ][ u64 jar_size LE ][ 8-byte magic "CFKGJAR1" ]
 *
 * On launch:
 *   1) Extract embedded JAR to %LOCALAPPDATA%\cache-fix-gui-kmp\app.jar (if needed)
 *   2) Find java.exe (bundled runtime\, JAVA_HOME, PATH)
 *   3) Run: java -jar app.jar [args...]   (default arg: gui)
 *
 * Cross-compile: x86_64-w64-mingw32-gcc -O2 -s -o stub.exe windows-launcher.c
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <windows.h>
#include <shlobj.h>

#define MAX_CMD 32768
#define MAX_PATH_LEN 4096
#define MAGIC "CFKGJAR1"
#define MAGIC_LEN 8
#define FOOTER_LEN 16 /* 8 size + 8 magic */

static void fail(const char *msg) {
    MessageBoxA(NULL, msg, "cache-fix-gui-kmp", MB_OK | MB_ICONERROR);
    /* Also try stderr if a console is attached */
    fprintf(stderr, "cache-fix-gui-kmp: %s\n", msg);
}

static int file_exists(const char *path) {
    DWORD a = GetFileAttributesA(path);
    return (a != INVALID_FILE_ATTRIBUTES) && !(a & FILE_ATTRIBUTE_DIRECTORY);
}

static int dir_exists(const char *path) {
    DWORD a = GetFileAttributesA(path);
    return (a != INVALID_FILE_ATTRIBUTES) && (a & FILE_ATTRIBUTE_DIRECTORY);
}

static void dirname_of(const char *path, char *out, size_t outsz) {
    strncpy(out, path, outsz - 1);
    out[outsz - 1] = '\0';
    char *slash = strrchr(out, '\\');
    if (!slash) slash = strrchr(out, '/');
    if (slash) *slash = '\0';
    else {
        out[0] = '.';
        out[1] = '\0';
    }
}

static int ensure_dir(const char *path) {
    if (dir_exists(path)) return 1;
    /* SHCreateDirectoryExA creates parents */
    int rc = SHCreateDirectoryExA(NULL, path, NULL);
    return rc == ERROR_SUCCESS || rc == ERROR_ALREADY_EXISTS || rc == ERROR_FILE_EXISTS;
}

/** UNC (\\server\share) and WSL paths cannot be CreateProcess / cmd current directory. */
static int is_unc_or_wsl_path(const char *p) {
    if (!p || !p[0]) return 0;
    if (p[0] == '\\' && p[1] == '\\') return 1;
    if (p[0] == '/' && p[1] == '/') return 1;
    /* defensive: sometimes shown without double-backslash in odd mounts */
    if (_strnicmp(p, "wsl.localhost", 13) == 0) return 1;
    if (_strnicmp(p, "wsl$", 4) == 0) return 1;
    return 0;
}

/** Prefer LOCALAPPDATA\cache-fix-gui-kmp; never return UNC. */
static int resolve_work_dir(const char *app_dir, char *out, size_t outsz) {
    char *local = getenv("LOCALAPPDATA");
    if (local && local[0] && !is_unc_or_wsl_path(local)) {
        snprintf(out, outsz, "%s\\cache-fix-gui-kmp", local);
        if (ensure_dir(out)) return 1;
    }
    char *temp = getenv("TEMP");
    if (temp && temp[0] && !is_unc_or_wsl_path(temp)) {
        snprintf(out, outsz, "%s\\cache-fix-gui-kmp", temp);
        if (ensure_dir(out)) return 1;
    }
    if (app_dir && app_dir[0] && !is_unc_or_wsl_path(app_dir)) {
        strncpy(out, app_dir, outsz - 1);
        out[outsz - 1] = '\0';
        return 1;
    }
    /* Last resort: Windows directory is always a valid drive path */
    UINT n = GetWindowsDirectoryA(out, (UINT)outsz);
    return n > 0 && n < outsz;
}

/* prefer_javaw=1 → no console window (GUI). prefer_javaw=0 → java.exe for CLI stdout. */
static int find_java(const char *app_dir, int prefer_javaw, char *java_out, size_t java_sz) {
    char candidate[MAX_PATH_LEN];
    const char *names_gui[] = { "javaw.exe", "java.exe", NULL };
    const char *names_cli[] = { "java.exe", "javaw.exe", NULL };
    const char **names = prefer_javaw ? names_gui : names_cli;

    for (int i = 0; names[i]; i++) {
        snprintf(candidate, sizeof(candidate), "%s\\runtime\\bin\\%s", app_dir, names[i]);
        if (file_exists(candidate)) {
            strncpy(java_out, candidate, java_sz - 1);
            java_out[java_sz - 1] = '\0';
            return 1;
        }
    }

    char *java_home = getenv("JAVA_HOME");
    if (java_home && java_home[0]) {
        for (int i = 0; names[i]; i++) {
            snprintf(candidate, sizeof(candidate), "%s\\bin\\%s", java_home, names[i]);
            if (file_exists(candidate)) {
                strncpy(java_out, candidate, java_sz - 1);
                java_out[java_sz - 1] = '\0';
                return 1;
            }
        }
    }

    for (int i = 0; names[i]; i++) {
        if (SearchPathA(NULL, names[i], NULL, (DWORD)java_sz, java_out, NULL) > 0) {
            return 1;
        }
    }
    return 0;
}

/* Read footer; return 1 and set *jar_size / *jar_offset if embedded payload present. */
static int read_embedded_footer(HANDLE h, uint64_t *jar_size, uint64_t *jar_offset) {
    LARGE_INTEGER file_size;
    if (!GetFileSizeEx(h, &file_size)) return 0;
    if (file_size.QuadPart < (LONGLONG)(FOOTER_LEN + 1)) return 0;

    LARGE_INTEGER seek;
    seek.QuadPart = file_size.QuadPart - FOOTER_LEN;
    if (!SetFilePointerEx(h, seek, NULL, FILE_BEGIN)) return 0;

    unsigned char footer[FOOTER_LEN];
    DWORD got = 0;
    if (!ReadFile(h, footer, FOOTER_LEN, &got, NULL) || got != FOOTER_LEN) return 0;

    if (memcmp(footer + 8, MAGIC, MAGIC_LEN) != 0) return 0;

    uint64_t size = 0;
    for (int i = 0; i < 8; i++) {
        size |= ((uint64_t)footer[i]) << (8 * i);
    }
    if (size == 0 || size > (uint64_t)file_size.QuadPart - FOOTER_LEN) return 0;

    *jar_size = size;
    *jar_offset = (uint64_t)file_size.QuadPart - FOOTER_LEN - size;
    return 1;
}

static int extract_embedded_jar(const char *exe_path, const char *dest_jar) {
    HANDLE h = CreateFileA(
        exe_path, GENERIC_READ, FILE_SHARE_READ, NULL,
        OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    if (h == INVALID_HANDLE_VALUE) {
        fail("Cannot open self executable to extract payload.");
        return 0;
    }

    uint64_t jar_size = 0, jar_offset = 0;
    if (!read_embedded_footer(h, &jar_size, &jar_offset)) {
        CloseHandle(h);
        return 0; /* not embedded */
    }

    /* Re-extract when size differs OR embedded exe is newer than cached jar. */
    if (file_exists(dest_jar)) {
        HANDLE existing = CreateFileA(
            dest_jar, GENERIC_READ, FILE_SHARE_READ, NULL,
            OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
        if (existing != INVALID_HANDLE_VALUE) {
            LARGE_INTEGER esz;
            FILETIME jarWrite = {0}, exeWrite = {0}, ftCreate, ftAccess;
            int size_ok = GetFileSizeEx(existing, &esz) && (uint64_t)esz.QuadPart == jar_size;
            GetFileTime(existing, &ftCreate, &ftAccess, &jarWrite);
            CloseHandle(existing);

            HANDLE hexe = CreateFileA(
                exe_path, GENERIC_READ, FILE_SHARE_READ, NULL,
                OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
            int exe_newer = 1;
            if (hexe != INVALID_HANDLE_VALUE) {
                GetFileTime(hexe, &ftCreate, &ftAccess, &exeWrite);
                CloseHandle(hexe);
                /* CompareFileTime: 1 if exeWrite > jarWrite */
                exe_newer = CompareFileTime(&exeWrite, &jarWrite) > 0;
            }
            if (size_ok && !exe_newer) {
                CloseHandle(h);
                return 1;
            }
        }
    }

    LARGE_INTEGER seek;
    seek.QuadPart = (LONGLONG)jar_offset;
    if (!SetFilePointerEx(h, seek, NULL, FILE_BEGIN)) {
        CloseHandle(h);
        fail("Seek to embedded jar failed.");
        return 0;
    }

    HANDLE out = CreateFileA(
        dest_jar, GENERIC_WRITE, 0, NULL,
        CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    if (out == INVALID_HANDLE_VALUE) {
        CloseHandle(h);
        fail("Cannot write extracted jar (check %%LOCALAPPDATA%% permissions).");
        return 0;
    }

    char buf[1024 * 256];
    uint64_t remaining = jar_size;
    while (remaining > 0) {
        DWORD chunk = remaining > sizeof(buf) ? (DWORD)sizeof(buf) : (DWORD)remaining;
        DWORD rd = 0;
        if (!ReadFile(h, buf, chunk, &rd, NULL) || rd == 0) {
            CloseHandle(out);
            CloseHandle(h);
            DeleteFileA(dest_jar);
            fail("Failed reading embedded jar payload.");
            return 0;
        }
        DWORD wr = 0;
        if (!WriteFile(out, buf, rd, &wr, NULL) || wr != rd) {
            CloseHandle(out);
            CloseHandle(h);
            DeleteFileA(dest_jar);
            fail("Failed writing extracted jar.");
            return 0;
        }
        remaining -= rd;
    }

    CloseHandle(out);
    CloseHandle(h);
    return 1;
}

static int resolve_jar(const char *exe_path, const char *app_dir, char *jar_out, size_t jar_sz) {
    /* 1) External jar next to exe (dev / override) */
    char external[MAX_PATH_LEN];
    snprintf(external, sizeof(external), "%s\\cache-fix-gui-kmp-all.jar", app_dir);
    if (file_exists(external)) {
        strncpy(jar_out, external, jar_sz - 1);
        jar_out[jar_sz - 1] = '\0';
        return 1;
    }

    /* 2) Embedded payload → always a local (non-UNC) data dir */
    char data_dir[MAX_PATH_LEN];
    if (!resolve_work_dir(app_dir, data_dir, sizeof(data_dir))) {
        fail("Cannot resolve a local data directory (LOCALAPPDATA/TEMP).");
        return 0;
    }
    /* Bump filename when shipping UI/theme changes so old caches are not reused. */
    snprintf(jar_out, jar_sz, "%s\\app-v11.jar", data_dir);

    if (extract_embedded_jar(exe_path, jar_out) && file_exists(jar_out)) {
        return 1;
    }

    fail(
        "No application payload found.\n\n"
        "This exe should be the single-file package (jar embedded),\n"
        "or place cache-fix-gui-kmp-all.jar next to the exe.");
    return 0;
}

int main(int argc, char **argv) {
    char exe_path[MAX_PATH_LEN];
    char app_dir[MAX_PATH_LEN];
    char jar_path[MAX_PATH_LEN];
    char java_path[MAX_PATH_LEN];
    char cmdline[MAX_CMD];

    if (GetModuleFileNameA(NULL, exe_path, MAX_PATH_LEN) == 0) {
        fail("GetModuleFileName failed.");
        return 1;
    }
    dirname_of(exe_path, app_dir, sizeof(app_dir));

    if (!resolve_jar(exe_path, app_dir, jar_path, sizeof(jar_path))) {
        return 1;
    }

    /* Default command: gui (Compose Desktop + tray) */
    int use_default_gui = (argc <= 1);
    int is_gui = use_default_gui;
    if (!use_default_gui && argc >= 2) {
        if (strcmp(argv[1], "gui") == 0 || strcmp(argv[1], "panel") == 0) {
            is_gui = 1;
        }
    }

    if (!find_java(app_dir, is_gui, java_path, sizeof(java_path))) {
        fail(
            "Java not found (javaw.exe / java.exe).\n\n"
            "Install Java 17+ and add it to PATH, or set JAVA_HOME,\n"
            "or place a JRE at:\n  <this-exe-folder>\\runtime\\bin\\javaw.exe");
        return 1;
    }

    /* Always use a local app home — launch folder is NOT controlled (WSL UNC, etc.). */
    char work_dir[MAX_PATH_LEN];
    if (!resolve_work_dir(app_dir, work_dir, sizeof(work_dir))) {
        fail("Cannot resolve a local working directory.");
        return 1;
    }

    /* -Duser.dir / -Dcache.fix.gui.home pin JVM away from exe launch directory */
    int n = snprintf(
        cmdline, sizeof(cmdline),
        "\"%s\" \"-Duser.dir=%s\" \"-Dcache.fix.gui.home=%s\" \"-Dfile.encoding=UTF-8\" -jar \"%s\"%s",
        java_path, work_dir, work_dir, jar_path,
        use_default_gui ? " gui" : "");
    if (n < 0 || n >= (int)sizeof(cmdline)) {
        fail("Command line too long.");
        return 1;
    }
    if (!use_default_gui) {
        for (int i = 1; i < argc; i++) {
            size_t left = sizeof(cmdline) - strlen(cmdline) - 1;
            int m = snprintf(cmdline + strlen(cmdline), left, " \"%s\"", argv[i]);
            if (m < 0 || (size_t)m >= left) {
                fail("Command line too long.");
                return 1;
            }
        }
    }

    /* GUI PE + javaw.exe = no black CMD. Do not use CREATE_NO_WINDOW (breaks AWT). */
    FreeConsole();

    STARTUPINFOA si;
    PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof(si));
    si.cb = sizeof(si);
    ZeroMemory(&pi, sizeof(pi));

    if (!CreateProcessA(
            NULL,
            cmdline,
            NULL,
            NULL,
            FALSE,
            0,
            NULL,
            work_dir,
            &si,
            &pi)) {
        char msg[768];
        snprintf(
            msg, sizeof(msg),
            "CreateProcess failed (%lu).\n\nCmd: %.300s\nCwd: %.200s",
            GetLastError(), cmdline, work_dir);
        fail(msg);
        return 1;
    }

    /* For GUI: don't keep a parent process waiting with a console.
       Wait so exit code propagates for CLI; for gui also wait (tray lives in JVM). */
    WaitForSingleObject(pi.hProcess, INFINITE);
    DWORD code = 1;
    GetExitCodeProcess(pi.hProcess, &code);
    CloseHandle(pi.hProcess);
    CloseHandle(pi.hThread);
    return (int)code;
}
