import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Backend configuration from local.properties, which is gitignored.
 * See supabase.properties.template for what each value is and which ones are safe here.
 *
 * Missing values become empty strings rather than failing the build, so the project still
 * compiles on a fresh clone. The app checks them at startup and says what is missing.
 */
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun config(key: String): String = localProperties.getProperty(key).orEmpty()

android {
    namespace = "com.example.stressguard"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.stressguard"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SUPABASE_URL", "\"${config("supabase.url")}\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"${config("supabase.publishableKey")}\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${config("supabase.googleWebClientId")}\"")
    }

    buildFeatures {
        buildConfig = true
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
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation("com.google.android.gms:play-services-wearable:18.1.0")
    implementation("androidx.health.connect:connect-client:1.1.0-alpha07")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    // Supabase: auth today, postgrest for profiles and stress history next.
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.ktor.client.okhttp)

    // Google sign-in through Credential Manager, which replaces the deprecated
    // GoogleSignInClient from play-services-auth.
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Real org.json for unit tests: the one bundled in android.jar is a stub that throws,
    // so tests that read the model manifest need an actual implementation on the classpath.
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
