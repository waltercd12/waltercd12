plugins { id("com.android.application") }
android {
    namespace = "nl.radioschipluiden.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "nl.radioschipluiden.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "1.2.0"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
