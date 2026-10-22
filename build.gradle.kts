import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3") // Jangan diganti ke versi terbaru, ada masalah dengan versi terbaru
        classpath("com.github.recloudstream:gradle:-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) = extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: BaseExtension.() -> Unit) = extensions.getByName<BaseExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/diioradhitya/CS3xHermes")
        authors = listOf("Dio R")
    }

    android {
        // Namespace diambil dari nama folder provider (contoh: HarufilmProvider -> com.harufilm)
        namespace = "com.${project.name.removeSuffix("Provider").lowercase()}"

        defaultConfig {
            // minSdk 21 (bukan 26): D8 memetakan minApi >= 26 ke format dex
            // "038" (dex\n038), sedangkan ART Android 6.0.1 (API 23) hanya
            // paham dex "037" -> "Unrecognized version number in ...cs3: 0 3 8"
            // lalu ClassNotFoundException, jadi SEMUA plugin gagal dimuat di
            // TV box Android 6. minSdk 21 membuat D8 keluarkan dex 037.
            // Pakai API level > 21 harus dicek manual (mis. android.util.Base64
            // untuk API 8+, BUKAN java.util.Base64 yang butuh API 26).
            minSdk = 21
            compileSdkVersion(35)
            targetSdk = 35
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
        }

        tasks.withType<KotlinJvmCompile> {
            compilerOptions {
                // cloudstream3:pre-release is compiled with JVM target 11, so this must be 11 too.
                // At JVM_1_8, inlining AppUtils.tryParseJson fails with:
                //   "Cannot inline bytecode built with JVM target 11 into bytecode that is being built with JVM target 1.8"
                jvmTarget.set(JvmTarget.JVM_11)
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions"
                )
                allWarningsAsErrors.set(false)
            }
        }
    }

    dependencies {
        val cloudstream by configurations
        val implementation by configurations

        // Cloudstream dependencies
        cloudstream("com.lagradost:cloudstream3:pre-release")

        // Other dependencies
        implementation(kotlin("stdlib"))
        implementation("com.github.Blatzar:NiceHttp:0.4.13")
        implementation("org.jsoup:jsoup:1.19.1")
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.0") // JANGAN DIGANTI ke versi terbaru
        implementation("com.fasterxml.jackson.core:jackson-databind:2.16.0")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
        implementation("org.mozilla:rhino:1.8.0")
        implementation("me.xdrop:fuzzywuzzy:1.4.0")
        implementation("com.google.code.gson:gson:2.11.0")
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
        implementation("app.cash.quickjs:quickjs-android:0.9.2")
        implementation("com.squareup.okhttp3:okhttp:4.12.0")
        implementation("androidx.core:core-ktx:1.16.0")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    }
}

task<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
