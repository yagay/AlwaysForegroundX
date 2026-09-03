plugins {
    id("com.android.application")
}

android {
    namespace = "com.jieei.alwaysforeground"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.jieei.alwaysforeground"
        minSdk = 31
        targetSdk = 37
        versionCode = 14
        versionName = "1.4.0"
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
