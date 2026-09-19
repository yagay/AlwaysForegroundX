plugins {
    id("com.android.application")
}

android {
    namespace = "com.yagay.alwaysforeground"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.yagay.MiniWindowGuard"
        minSdk = 31
        targetSdk = 37
        versionCode = 43
        versionName = "2.2.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
}
