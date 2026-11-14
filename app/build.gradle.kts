plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.photerest.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.photerest.app.material"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "1.4.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["notAnnotation"] = "com.photerest.app.PermissionScenario"
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { animationsDisabled = true }
}
dependencies {
    val bom = platform("androidx.compose:compose-bom:2025.09.01")
    implementation(bom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3:1.5.0-alpha06")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("androidx.room:room-runtime:2.6.1")
    annotationProcessor("androidx.room:room-compiler:2.6.1")
    implementation("androidx.exifinterface:exifinterface:1.3.6")
    implementation("androidx.media3:media3-exoplayer:1.7.1")
    implementation("androidx.media3:media3-ui:1.7.1")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(bom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.work:work-testing:2.10.5")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
