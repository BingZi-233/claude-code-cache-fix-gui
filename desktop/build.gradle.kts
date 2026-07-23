import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    jvmToolchain(17)
}

// Windows skiko natives only (avoid desktop-jvm-windows-x64 variant ambiguity on Linux).
val windowsNatives by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
}

dependencies {
    implementation(project(":shared"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    testImplementation(kotlin("test"))

    windowsNatives("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:0.8.18")
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "com.cachefix.gui.cli.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "cache-fix-gui-kmp"
            packageVersion = "1.0.0"
            description = "Claude Code cache-fix control panel (Compose)"
            copyright = "MIT"
            vendor = "cache-fix-gui"
            windows {
                menuGroup = "cache-fix-gui"
                upgradeUuid = "A1B2C3D4-E5F6-7890-ABCD-EF1234567890"
                iconFile.set(project.file("src/main/resources/app-icon.ico"))
            }
            linux {
                iconFile.set(project.file("src/main/resources/app-icon.png"))
            }
            macOS {
                // Prefer .icns (CI generates it); fall back to PNG for local builds.
                val icns = project.file("src/main/resources/app-icon.icns")
                val png = project.file("src/main/resources/app-icon.png")
                iconFile.set(if (icns.exists()) icns else png)
                bundleID = "com.cachefix.gui"
            }
        }
    }
}

tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Fat jar with Compose + controller (current OS natives)"
    archiveBaseName.set("cache-fix-gui-kmp")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "com.cachefix.gui.cli.MainKt"
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.exists() }
            .map { if (it.isDirectory) it else zipTree(it) }
    })
}

tasks.register<Jar>("fatJarWindows") {
    group = "build"
    description = "Fat jar including Windows x64 skiko/Compose natives (for PE packaging)"
    archiveBaseName.set("cache-fix-gui-kmp")
    archiveClassifier.set("windows-all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "com.cachefix.gui.cli.MainKt"
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath, windowsNatives)
    from({
        (configurations.runtimeClasspath.get() + windowsNatives)
            .filter { it.exists() }
            .map { if (it.isDirectory) it else zipTree(it) }
    })
}
