plugins { id("com.android.application") }

val browserAbi = providers.gradleProperty("devxyz.abi").orElse("arm64-v8a").get()

android {
    namespace = "com.jepongdevxyz.browser"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.jepongdevxyz.browser"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += browserAbi }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation("androidx.activity:activity:1.11.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("org.mozilla.geckoview:geckoview:156.0.20260921121718")
}
