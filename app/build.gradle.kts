plugins {
	id("com.android.application")
	kotlin("android")
	kotlin("kapt")
	alias(libs.plugins.kotlin.serialization)
	alias(libs.plugins.aboutlibraries)
}

android {
	namespace = "org.jellyfin.androidtv"
	compileSdk = 33

	defaultConfig {
		minSdk = 21
		targetSdk = 33

		// Release version
		applicationId = namespace
		versionName = project.getVersionName()
		versionCode = getVersionCode(versionName!!)
		setProperty("archivesBaseName", "jellyfin-androidtv-v$versionName")
	}

	sourceSets["main"].java.srcDirs("src/main/kotlin")
	sourceSets["test"].java.srcDirs("src/test/kotlin")

	buildFeatures {
		viewBinding = true
	}

	compileOptions {
		isCoreLibraryDesugaringEnabled = true
	}

	buildTypes {
		val release by getting {
			isMinifyEnabled = false

			// Set package names used in various XML files
			resValue("string", "app_id", namespace!!)
			resValue("string", "app_search_suggest_authority", "${namespace}.content")
			resValue("string", "app_search_suggest_intent_data", "content://${namespace}.content/intent")

			// Set flavored application name
			resValue("string", "app_name", "@string/app_name_release")

			buildConfigField("boolean", "DEVELOPMENT", "false")
		}

		val debug by getting {
			// Use different application id to run release and debug at the same time
			applicationIdSuffix = ".debug"

			// Set package names used in various XML files
			resValue("string", "app_id", namespace + applicationIdSuffix)
			resValue("string", "app_search_suggest_authority", "${namespace + applicationIdSuffix}.content")
			resValue("string", "app_search_suggest_intent_data", "content://${namespace + applicationIdSuffix}.content/intent")

			// Set flavored application name
			resValue("string", "app_name", "@string/app_name_debug")

			buildConfigField("boolean", "DEVELOPMENT", (defaultConfig.versionCode!! < 100).toString())
		}
	}

	lint {
		lintConfig = file("$rootDir/android-lint.xml")
		abortOnError = false
		sarifReport = true
		checkDependencies = true
	}

	testOptions.unitTests.all {
		it.useJUnitPlatform()
	}
}

aboutLibraries {
	// Remove the "generated" timestamp to allow for reproducible builds
	excludeFields = arrayOf("generated")
}

val versionTxt by tasks.registering {
	val path = buildDir.resolve("version.txt")

	doLast {
		val versionString = "v${android.defaultConfig.versionName}=${android.defaultConfig.versionCode}"
		logger.info("Writing [$versionString] to $path")
		path.writeText("$versionString\n")
	}
}

dependencies {
	// Jellyfin
	implementation(projects.playback.core)
	implementation(projects.playback.exoplayer)
	implementation(projects.playback.jellyfin)
	implementation(projects.playback.ui)
	implementation(projects.preference)
	implementation(libs.jellyfin.apiclient)
	implementation(libs.jellyfin.sdk) {
		// Change version if desired
		val sdkVersion = findProperty("sdk.version")?.toString()
		when (sdkVersion) {
			"local" -> version { strictly("latest-SNAPSHOT") }
			"snapshot" -> version { strictly("master-SNAPSHOT") }
			"unstable-snapshot" -> version { strictly("openapi-unstable-SNAPSHOT") }
		}
	}

	// Kotlin
	implementation(libs.kotlinx.coroutines)
	implementation(libs.kotlinx.serialization.json)

	// Android(x)
	implementation(libs.androidx.core)
	implementation(libs.androidx.activity)
	implementation(libs.androidx.fragment)
	implementation(libs.androidx.leanback.core)
	implementation(libs.androidx.leanback.preference)
	implementation(libs.androidx.preference)
	implementation(libs.androidx.appcompat)
	implementation(libs.androidx.tvprovider)
	implementation(libs.androidx.constraintlayout)
	implementation(libs.androidx.recyclerview)
	implementation(libs.androidx.work.runtime)
	implementation(libs.bundles.androidx.lifecycle)
	implementation(libs.androidx.window)
	implementation(libs.androidx.cardview)
	implementation(libs.androidx.startup)

	// Dependency Injection
	implementation(libs.bundles.koin)

	// GSON
	implementation(libs.gson)

	// Media players
	implementation(libs.exoplayer)
	implementation(libs.jellyfin.exoplayer.ffmpegextension)
	implementation(libs.libvlc)

	// Markdown
	implementation(libs.bundles.markwon)

	// Image utility
	implementation(libs.blurhash)
	implementation(libs.glide.core)
	kapt(libs.glide.compiler)
	implementation(libs.kenburnsview)

	// Crash Reporting
	implementation(libs.bundles.acra)

	// Licenses
	implementation(libs.aboutlibraries)

	// Logging
	implementation(libs.timber)
	implementation(libs.slf4j.timber)

	// Debugging
	if (getProperty("leakcanary.enable")?.toBoolean() == true)
		debugImplementation(libs.leakcanary)

	// Compatibility (desugaring)
	coreLibraryDesugaring(libs.android.desugar)

	// Testing
	testImplementation(libs.kotest.runner.junit5)
	testImplementation(libs.kotest.assertions)
	testImplementation(libs.mockk)
}
