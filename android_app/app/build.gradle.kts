plugins {
	id("com.android.application")
	id("org.jetbrains.kotlin.android")
	id("org.jetbrains.kotlin.plugin.compose")
}

import java.io.FileInputStream
import java.util.Properties

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
	FileInputStream(keystorePropertiesFile).use(keystoreProperties::load)
}

val generatedBrandingResources = layout.buildDirectory.dir("generated/asterion-branding/res").get().asFile
val prepareAsterionBrandingResources by tasks.registering(Sync::class) {
	from(layout.projectDirectory.dir("../../branding")) {
		include("AsterionCore-Logo.png")
		rename { "asterioncore_logo.png" }
		into("drawable-nodpi")
	}
	from(layout.projectDirectory.dir("../../branding")) {
		include("AsterionCore-Splash.png")
		rename { "asterioncore_splash.png" }
		into("drawable-nodpi")
	}
	from(layout.projectDirectory.dir("../../branding")) {
		include("AsterionCore-Icon-512.png")
		rename { "asterioncore_icon.png" }
		into("drawable-nodpi")
	}
	from(layout.projectDirectory.dir("../../branding")) {
		include("AsterionCore-Icon-512.png")
		rename { "ic_launcher.png" }
		into("mipmap-nodpi")
	}
	from(layout.projectDirectory.dir("../../branding")) {
		include("AsterionCore-Icon-512.png")
		rename { "ic_launcher_round.png" }
		into("mipmap-nodpi")
	}
	into(generatedBrandingResources)
}

android {
	namespace = "com.ailm.android"
	compileSdk = 35
	// The current Android lint parser produces false-positive SuspiciousIndentation
	// diagnostics for correctly nested Kotlin/Compose expressions. Keep them
	// visible as warnings instead of suppressing the entire lint check.
	lint {
		warning += "SuspiciousIndentation"
	}

	defaultConfig {
		applicationId = "com.ailm.android"
		minSdk = 30
		targetSdk = 35
		versionCode = 20002
		versionName = "2.0.2"
		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
		externalNativeBuild {
			cmake {
				cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
			}
		}
		ndk {
			abiFilters += setOf("arm64-v8a")
		}
	}

	signingConfigs {
		if (keystorePropertiesFile.exists()) {
			create("release") {
				storeFile = file(keystoreProperties.getProperty("storeFile"))
				storePassword = keystoreProperties.getProperty("storePassword")
				keyAlias = keystoreProperties.getProperty("keyAlias")
				keyPassword = keystoreProperties.getProperty("keyPassword")
			}
		}
	}

	buildTypes {
		getByName("debug") {
			isMinifyEnabled = false
			buildConfigField("String", "RELEASE_CHANNEL", "\"debug\"")
		}
		getByName("release") {
			isMinifyEnabled = true
			isShrinkResources = true
			proguardFiles(
				getDefaultProguardFile("proguard-android-optimize.txt"),
				"proguard-rules.pro",
			)
			if (keystorePropertiesFile.exists()) {
				signingConfig = signingConfigs.getByName("release")
			}
			buildConfigField("String", "RELEASE_CHANNEL", "\"stable\"")
		}
	}

	buildFeatures {
		compose = true
		buildConfig = true
	}

	sourceSets.getByName("main").res.srcDir(generatedBrandingResources)

	externalNativeBuild {
		cmake {
			path = file("src/main/cpp/CMakeLists.txt")
			version = "3.22.1"
		}
	}

	packaging {
		resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}

	kotlinOptions {
		jvmTarget = "17"
	}
}

tasks.named("preBuild").configure {
	dependsOn(prepareAsterionBrandingResources)
}

dependencies {
	implementation("androidx.core:core-ktx:1.15.0")
	implementation("androidx.core:core-splashscreen:1.0.1")
	implementation("androidx.activity:activity-compose:1.10.0")
	implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
	implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
	implementation("androidx.navigation:navigation-compose:2.8.5")
	implementation("androidx.work:work-runtime-ktx:2.10.0")
	implementation("androidx.datastore:datastore-preferences:1.2.1")
	implementation("androidx.graphics:graphics-path:1.1.0")
	implementation("androidx.paging:paging-runtime-ktx:3.3.6")
	implementation("androidx.room:room-runtime:2.6.1")
	implementation("androidx.room:room-ktx:2.6.1")
	implementation("androidx.documentfile:documentfile:1.0.1")
	implementation("io.coil-kt:coil-compose:2.7.0")
	implementation("io.coil-kt:coil-gif:2.7.0")
	implementation("com.microsoft.onnxruntime:onnxruntime-android:1.28.0")
	implementation("org.tensorflow:tensorflow-lite:2.16.1")
	implementation("androidx.compose.ui:ui:1.7.6")
	implementation("androidx.compose.material3:material3:1.3.1")
	implementation("androidx.compose.ui:ui-tooling-preview:1.7.6")
	debugImplementation("androidx.compose.ui:ui-tooling:1.7.6")
	testImplementation("junit:junit:4.13.2")
	testImplementation("org.json:json:20240303")
}
