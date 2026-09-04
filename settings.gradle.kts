rootProject.name = "CS3xHermes"

// Auto-include semua provider folders (mirip Hatsune)
File(rootDir, ".").listFiles()!!
    .filter { it.isDirectory && it.name.endsWith("Provider") && File(it, "build.gradle.kts").exists() }
    .forEach { include(it.name) }
