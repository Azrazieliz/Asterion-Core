plugins {
	id("com.android.application")
	id("org.jetbrains.kotlin.android")
	id("org.jetbrains.kotlin.plugin.compose")
}

import java.io.FileInputStream
import java.util.ArrayDeque
import java.util.Properties
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
	FileInputStream(keystorePropertiesFile).use(keystoreProperties::load)
}

// Release builds use one persistent keystore. In GitHub Actions the key is
// supplied exclusively through encrypted repository secrets and runner temp.
// The local keystore.properties fallback remains available for offline builds.
val ciKeystoreFile = System.getenv("ASTERION_SIGNING_KEYSTORE_FILE").orEmpty().trim()
val ciStorePassword = System.getenv("ASTERION_SIGNING_STORE_PASSWORD").orEmpty()
val ciKeyAlias = System.getenv("ASTERION_SIGNING_KEY_ALIAS").orEmpty().trim()
val ciKeyPassword = System.getenv("ASTERION_SIGNING_KEY_PASSWORD").orEmpty()
val hasCiSigning = ciKeystoreFile.isNotBlank() &&
	ciStorePassword.isNotBlank() &&
	ciKeyAlias.isNotBlank() &&
	ciKeyPassword.isNotBlank() &&
	file(ciKeystoreFile).isFile
val hasLocalSigning = keystorePropertiesFile.exists()
val hasReleaseSigning = hasCiSigning || hasLocalSigning

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

	doLast {
		val source = generatedBrandingResources.resolve("drawable-nodpi/asterioncore_logo.png")
		val target = generatedBrandingResources.resolve("drawable-nodpi/asterioncore_logo_splash.png")
		val input = ImageIO.read(source)
		require(input != null) { "Unable to decode canonical AsterionCore logo." }

		val width = input.width
		val height = input.height
		val output = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
		for (y in 0 until height) {
			for (x in 0 until width) {
				output.setRGB(x, y, input.getRGB(x, y))
			}
		}

		fun removableBackground(argb: Int): Boolean {
			val alpha = (argb ushr 24) and 0xFF
			if (alpha == 0) return true
			val red = (argb ushr 16) and 0xFF
			val green = (argb ushr 8) and 0xFF
			val blue = argb and 0xFF
			val maximum = maxOf(red, green, blue)
			val minimum = minOf(red, green, blue)
			return maximum <= 44 && (maximum - minimum) <= 18
		}

		val visited = BooleanArray(width * height)
		val queue = ArrayDeque<Int>()
		fun enqueue(x: Int, y: Int) {
			if (x !in 0 until width || y !in 0 until height) return
			val index = y * width + x
			if (visited[index]) return
			if (!removableBackground(output.getRGB(x, y))) return
			visited[index] = true
			queue.addLast(index)
		}

		for (x in 0 until width) {
			enqueue(x, 0)
			enqueue(x, height - 1)
		}
		for (y in 0 until height) {
			enqueue(0, y)
			enqueue(width - 1, y)
		}

		while (queue.isNotEmpty()) {
			val index = queue.removeFirst()
			val x = index % width
			val y = index / width
			output.setRGB(x, y, output.getRGB(x, y) and 0x00FFFFFF)
			enqueue(x - 1, y)
			enqueue(x + 1, y)
			enqueue(x, y - 1)
			enqueue(x, y + 1)
		}

		ImageIO.write(output, "png", target)
	}
}

android {
	namespace = "com.ailm.android"
	compileSdk = 35

	defaultConfig {
		applicationId = "com.ailm.android"
		minSdk = 30
		targetSdk = 35
		versionCode = 20007
		versionName = "2.0.7"
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
		if (hasReleaseSigning) {
			create("release") {
				storeFile = file(if (hasCiSigning) ciKeystoreFile else keystoreProperties.getProperty("storeFile"))
				storePassword = if (hasCiSigning) ciStorePassword else keystoreProperties.getProperty("storePassword")
				keyAlias = if (hasCiSigning) ciKeyAlias else keystoreProperties.getProperty("keyAlias")
				keyPassword = if (hasCiSigning) ciKeyPassword else keystoreProperties.getProperty("keyPassword")
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
			if (hasReleaseSigning) {
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
