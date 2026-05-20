plugins {
    id("com.android.application")
}

android {
    namespace = "com.sharn.trafficsafety"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sharn.trafficsafety"
        minSdk = 24
        targetSdk = 34
        versionCode = 8
        versionName = "2.3.2"
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        mlModelBinding = true
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    
    // CameraX 相機支援
    implementation("androidx.camera:camera-core:1.3.0")
    implementation("androidx.camera:camera-camera2:1.3.0")
    implementation("androidx.camera:camera-lifecycle:1.3.0")
 implementation("androidx.camera:camera-view:1.3.0")
    
    // TensorFlow Lite
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    implementation("org.tensorflow:tensorflow-lite-metadata:0.1.0")
    
    // 物體偵測模型
    implementation("org.tensorflow:tensorflow-lite-task-vision:0.4.4")
    
    // v2.3: 紅綠燈偵測使用 TFLite，不需要 OpenCV
}
