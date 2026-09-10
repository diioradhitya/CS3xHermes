rootProject.name = "CS3xHermes"

// Auto-include semua provider folders (self-contained layout)
// Include folder mana pun yang punya build.gradle.kts di root-nya
File(rootDir, ".").listFiles()!!
    .filter { it.isDirectory && File(it, "build.gradle.kts").exists() && it.name != ".github" }
    .forEach { include(it.name) }
