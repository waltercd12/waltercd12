plugins { id("com.android.application") }
android {
    namespace = "nl.radioschipluiden.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "nl.radioschipluiden.radio"
        minSdk = 26
        targetSdk = 35
        versionCode = 21
        versionName = "2.1.0"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
