import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.chaquopy)
}

// Path to Python 3.10 on the build machine. Order of priority:
// 1) local.properties -> chaquopy.buildPython=C:/Users/andre/.../python.exe
// 2) CHAQUOPY_BUILD_PYTHON environment variable
// 3) System default: "py -3.10" on Windows, "python3.10" on other systems
val localProps = Properties().apply {
    rootProject.file("local.properties")
        .takeIf { it.exists() }
        ?.inputStream()?.use { load(it) }
}

fun chaquopyBuildPython(): List<String> {
    localProps.getProperty("chaquopy.buildPython")?.let { return listOf(it) }
    System.getenv("CHAQUOPY_BUILD_PYTHON")?.let { return listOf(it) }
    return if (System.getProperty("os.name").startsWith("Windows")) {
        listOf("py", "-3.10")
    } else {
        listOf("python3.10")
    }
}

android {
    namespace = "com.android.lorablueedge"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.android.lorablueedge"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
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


    // Paho's MQTT client packages a duplicate META-INF license/notice file
    // that collides with other libraries during packaging. Without this
    // exclusion the build fails at the merge step with a "More than one
    // file was found with OS independent path" error.
    packaging {
        resources {
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/LICENSE.md"
        }
    }
}

chaquopy {
    defaultConfig {
        version = "3.10"
        buildPython(*chaquopyBuildPython().toTypedArray())
        pip {
            install("numpy==1.23.3")
            install("scipy==1.8.1")
            install("scikit-learn==1.3.2")
            install("joblib==1.6.0")
            install("threadpoolctl==3.7.0")
        }
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    implementation("androidx.activity:activity-ktx:1.8.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Required by MqttConfigDialog (DialogFragment + supportFragmentManager)
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    // Konker MQTT publishing — same Paho client used by the wifiradar
    // reference app. org.eclipse.paho.client.mqttv3 alone is enough for a
    // plain TCP MQTT client (MqttPublisher uses MemoryPersistence, no
    // android.service component, so the android.service artifact is not
    // needed here).
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
}