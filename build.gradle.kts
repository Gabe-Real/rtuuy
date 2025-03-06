import dev.kordex.gradle.plugins.kordex.DataCollection

plugins {
	alias(libs.plugins.kotlin.jvm)
	alias(libs.plugins.kotlin.serialization)

	alias(libs.plugins.shadow)
	//alias(libs.plugins.detekt)

	alias(libs.plugins.kordex.plugin)
}

var javaVersion = 21

group = "dev.upcraft"
version = "1.0.0-SNAPSHOT"

dependencies {
	//detektPlugins(libs.detekt)

	implementation(libs.kotlin.stdlib)
	implementation(libs.kx.ser)

	// Logging dependencies
	implementation(libs.groovy)
	implementation(libs.jansi)
	implementation(libs.logback)
	implementation(libs.logback.groovy)
	implementation(libs.logging)
}

kordEx {
	kordExVersion = libs.versions.kordex.asProvider()

	bot {
		// See https://docs.kordex.dev/data-collection.html
		dataCollection(DataCollection.None)
		//dataCollection(DataCollection.Standard)

		mainClass = "dev.upcraft.rtuuy.AppKt"
	}

	i18n {
		classPackage = "dev.upcraft.rtuuy.i18n"
		translationBundle = "rtuuy.strings"
	}

	module("data-mongodb")
}

// The linter is currently disabled in order to allow building, it may be re-enabled later
/*
detekt {
	buildUponDefaultConfig = true

	config.from(rootProject.files("detekt.yml"))
}
*/

kotlin {
	jvmToolchain {
		languageVersion.set(JavaLanguageVersion.of(javaVersion))
	}
}
