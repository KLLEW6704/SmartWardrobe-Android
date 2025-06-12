// 在文件最顶部加入这两个import语句
import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// [关键修正]：将读取 local.properties 的逻辑移动到正确的位置
// 即 plugins { ... } 的下方, android { ... } 的上方
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    // 因为顶部已经import，所以这里不再需要写 java.io.FileInputStream
    localProperties.load(FileInputStream(localPropertiesFile))
}

android {
    namespace = "com.example.smartwardrobe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.smartwardrobe"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // buildConfigField 的代码位置是正确的，保持不变
        buildConfigField("String", "ZHIPU_API_KEY", "\"${localProperties.getProperty("ZHIPU_API_KEY") ?: ""}\"")
        buildConfigField("String", "WEATHER_API_KEY", "\"${localProperties.getProperty("WEATHER_API_KEY") ?: ""}\"")
    }


    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation ("com.google.android.material:material:1.9.0")
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.swiperefreshlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation ("com.squareup.retrofit2:retrofit:2.9.0")
    implementation ("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation ("androidx.appcompat:appcompat:1.6.1")
    implementation ("com.google.android.gms:play-services-location:21.0.1")
    implementation("io.coil-kt:coil:2.6.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.auth0:java-jwt:4.4.0")
    implementation ("androidx.cardview:cardview:1.0.0")
    implementation ("com.auth0.android:jwtdecode:2.0.0")
    implementation ("com.squareup.okhttp3:okhttp:4.9.0")
}