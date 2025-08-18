import java.io.File

buildscript {
    dependencies {
        if (rootProject.hasProperty("jacocoVersion")) {
            classpath("org.jacoco:org.jacoco.core:${rootProject.property("jacocoVersion")}")
        }
    }
}

plugins {
    id("com.android.application") version "8.1.4"
    id("com.github.ben-manes.versions") version "0.51.0"
    id("net.ltgt.errorprone") version "4.2.0"
    id("com.gladed.androidgitversion") version "0.4.14"
    id("com.github.kt3k.coveralls") version "2.12.2"
    id("com.mxalbert.gradle.jacoco-android") version "0.2.1"
    id("com.starter.easylauncher") version "6.4.0"
}

val testRunnerVersion = "1.5.0"
val espressoVersion = "3.5.1"

apply(plugin = "com.diffplug.spotless")
apply(from = "../config/quality.gradle")

// Coveralls configuration - commented out for now
// coveralls {
//     jacocoReportPath = "build/reports/coverage/google/debug/report.xml"
// }

androidGitVersion {
    prefix = "v"
    codeFormat = "MMNNPPBBB"
}

android {
    namespace = "org.connectbot"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.connectbot"
        versionName = androidGitVersion.name()
        versionCode = androidGitVersion.code()

        minSdk = 21
        targetSdk = 34

        vectorDrawables.useSupportLibrary = true

        ndk {
            abiFilters += listOf("x86", "x86_64", "armeabi-v7a", "arm64-v8a")
        }

        testInstrumentationRunner = "org.connectbot.ConnectbotJUnitRunner"

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_TOOLCHAIN=clang")
                cppFlags += listOf("-std=c++11", "-frtti", "-fexceptions")
            }
        }

        multiDexEnabled = true
        
        javaCompileOptions {
            annotationProcessorOptions {
                arguments["resourcePackageName"] = "org.connectbot"
                arguments["androidManifestFile"] = "$projectDir/AndroidManifest.xml"
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (project.hasProperty("keystorePassword")) {
            create("release") {
                storeFile = file(property("keystoreFile") as String)
                storePassword = property("keystorePassword") as String
                keyAlias = property("keystoreAlias") as String
                keyPassword = property("keystorePassword") as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard.cfg"
            )
            testProguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard.cfg",
                "proguard-tests.cfg"
            )

            if (project.hasProperty("keystorePassword")) {
                signingConfig = signingConfigs.getByName("release")
            }
        }

        debug {
            // This is necessary to avoid using multiDex
            isMinifyEnabled = true

            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard.cfg",
                "proguard-debug.cfg"
            )
            testProguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard.cfg",
                "proguard-tests.cfg"
            )

            applicationIdSuffix = ".debug"
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true
        }
    }

    flavorDimensions += listOf("license", "variant")

    productFlavors {
        create("oss") {
            dimension = "license"
            versionNameSuffix = "-oss"
        }

        create("google") {
            dimension = "license"
            versionNameSuffix = ""
        }
        
        // A/B variants for safe updates without disconnection
        create("versionA") {
            dimension = "variant"
            applicationIdSuffix = ".a"
            versionNameSuffix = "-A"
            resValue("string", "app_name_variant", "SlopShell A")
        }
        
        create("versionB") {
            dimension = "variant"
            applicationIdSuffix = ".b"
            versionNameSuffix = "-B"
            resValue("string", "app_name_variant", "SlopShell B")
        }
    }

    testOptions {
        animationsDisabled = true

        unitTests {
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    ndkVersion = "25.1.8937393"

    lint {
        disable += setOf("UnusedAttribute", "ValidFragment", "InvalidPackage", "GradleDependency", "NewerVersionAvailable")
        checkReleaseBuilds = false
        abortOnError = true
        error += setOf("ExportedReceiver", "ExportedActivity", "ExportedService", "ExportedContentProvider")
        checkOnly += setOf("PackageManagerGetSignatures", "HardwareIds", "LogConditional", "GetInstance", "TrustAllX509TrustManager")
        lintConfig = file("$rootDir/config/lint.xml")
    }

    // EasyLauncher configuration would go here but needs proper type imports
    // For now, commenting out as it's not critical for the build
    /*
    easylauncher {
        buildTypes {
            register("debug") {
                // Custom ribbon configuration
            }
        }
    }
    */
}

// Safe deployment tasks for A/B versions
val adbPath = android.adbExecutable.absolutePath

tasks.register<Exec>("deployA") {
    description = "Build and deploy SlopShell A"
    dependsOn("assembleOssVersionADebug")
    
    val apk = "${layout.buildDirectory.get().asFile.absolutePath}/outputs/apk/ossVersionA/debug/app-oss-versionA-debug.apk"
    commandLine(adbPath, "install", "-r", apk)
    
    doFirst {
        println("🔵 Building and installing SlopShell A...")
    }
    
    doLast {
        file("${rootDir}/.last-deployment").writeText("A")
        println("✅ SlopShell A installed successfully!")
    }
}

tasks.register<Exec>("deployB") {
    description = "Build and deploy SlopShell B"
    dependsOn("assembleOssVersionBDebug")
    
    val apk = "${layout.buildDirectory.get().asFile.absolutePath}/outputs/apk/ossVersionB/debug/app-oss-versionB-debug.apk"
    commandLine(adbPath, "install", "-r", apk)
    
    doFirst {
        println("🟢 Building and installing SlopShell B...")
    }
    
    doLast {
        file("${rootDir}/.last-deployment").writeText("B")
        println("✅ SlopShell B installed successfully!")
    }
}

// Helper task to check which version was last deployed
tasks.register("checkDeployment") {
    description = "Check which version was last deployed"
    
    doLast {
        val deploymentStateFile = file("${rootDir}/.last-deployment")
        if (deploymentStateFile.exists()) {
            val lastDeployment = deploymentStateFile.readText().trim()
            println("📋 Last deployment: Version $lastDeployment")
            println("💡 To deploy the other version, run: ./gradlew deploy${if (lastDeployment == "A") "B" else "A"}")
        } else {
            println("📋 No previous deployment found")
            println("💡 Run either: ./gradlew deployA or ./gradlew deployB")
        }
    }
}

dependencies {
    implementation("org.connectbot:sshlib:2.2.23")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")

    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.preference:preference:1.2.1")
    implementation("androidx.viewpager:viewpager:1.0.0")
    
    implementation("com.google.android.material:material:1.12.0")

    val googleImplementation by configurations
    googleImplementation("com.google.android.gms:play-services-base:18.5.0") {
        exclude(group = "androidx.fragment")
    }

    // Removed sshlib project dependency as it's not needed

    androidTestImplementation("androidx.test:core:$testRunnerVersion")
    androidTestImplementation("androidx.test:runner:$testRunnerVersion")
    androidTestImplementation("androidx.test:rules:$testRunnerVersion")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("com.linkedin.testbutler:test-butler-library:2.2.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.test.ext:junit:1.1.5")
    testImplementation("org.mockito:mockito-core:5.17.0")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testImplementation("org.robolectric:robolectric:4.14.1")

    // Needed for robolectric tests
    testCompileOnly("org.conscrypt:conscrypt-openjdk-uber:2.5.2")
    testRuntimeOnly("org.conscrypt:conscrypt-android:2.5.3")
    testImplementation("org.conscrypt:conscrypt-openjdk-uber:2.5.2")

    errorprone("com.google.errorprone:error_prone_core:2.38.0")
}