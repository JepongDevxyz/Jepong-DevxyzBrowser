import java.util.Base64

plugins { id("com.android.application") }

android {
    namespace = "com.jepongdevxyz.browser"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.jepongdevxyz.browser"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
    // Slim build: package arm64 native libs only (Honor 400 is arm64).
    // Drops the other ABIs' .so files (~316MB) so the APK installs cleanly.
    defaultConfig {
        ndk {
            abiFilters += "arm64-v8a"
        }
    }
}

dependencies {
    implementation("androidx.activity:activity:1.11.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("org.mozilla.geckoview:geckoview:156.0.20260921121718")
}

// Real fox launcher icon: the repo cannot host binary drawables, so the fox
// ships as base64 text (assets/img/fox_icon.b64). This task decodes it at build
// time into a generated drawable so the launcher shows the real fox.
val foxResDir = layout.buildDirectory.dir("generated/foxres")
android {
    sourceSets.named("main") {
        res.srcDirs(foxResDir)
    }
}
val genFoxLauncherIcon by tasks.registering {
    val b64File = file("src/main/assets/img/fox_icon.b64")
    val outDir = foxResDir.map { it.dir("drawable-xxxhdpi") }
    inputs.file(b64File)
    outputs.dir(outDir)
    doLast {
        val dir = outDir.get().asFile
        dir.mkdirs()
        val clean = b64File.readText().filter { !it.isWhitespace() }
        val png = Base64.getDecoder().decode(clean)
        dir.resolve("ic_launcher_fox.png").writeBytes(png)
    }
}
tasks.named("preBuild") { dependsOn(genFoxLauncherIcon) }
