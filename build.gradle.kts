import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
    id("org.jetbrains.intellij.platform.grammarkit") version "2.19.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // CLion is the platform the plugin is compiled and tested against; only
        // platform and lang APIs are used, so the same build installs on PyCharm,
        // RustRover and IntelliJ IDEA (the plugin verifier covers them). The
        // unified IntelliJ IDEA distribution cannot open a light test project
        // (its Ultimate licensing startup activity fails to instantiate), so it
        // is not used here. Set -PplatformLocalPath=/path/to/CLion.app (or put it
        // in ~/.gradle/gradle.properties) to build against an installed IDE
        // instead of downloading one.
        val localPath = providers.gradleProperty("platformLocalPath")
        if (localPath.isPresent) local(localPath) else clion(providers.gradleProperty("platformVersion"))
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

// The IntelliJ Platform Gradle Plugin sets the Java and Kotlin JVM targets to
// what the selected platform build requires; do not override them here.

sourceSets {
    main {
        java.srcDirs("src/main/gen")
    }
}

tasks {
    generateLexer {
        sourceFile = file("src/main/grammar/Hgl.flex")
        targetOutputDir = file("src/main/gen/io/github/hhenson/hgl/lexer")
        purgeOldFiles = true
    }
    generateParser {
        sourceFile = file("src/main/grammar/Hgl.bnf")
        targetRootOutputDir = file("src/main/gen")
        pathToParser = "io/github/hhenson/hgl/parser/HglParser.java"
        pathToPsiRoot = "io/github/hhenson/hgl/psi"
        purgeOldFiles = true
    }
    compileKotlin {
        dependsOn(generateLexer, generateParser)
    }
    compileJava {
        dependsOn(generateLexer, generateParser)
    }
    wrapper {
        gradleVersion = "9.8.0"
        distributionType = Wrapper.DistributionType.BIN
    }
}

intellijPlatform {
    pluginConfiguration {
        id = providers.gradleProperty("pluginGroup")
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            create(IntelliJPlatformType.CLion, "2026.2")
            create(IntelliJPlatformType.PyCharm, "2026.2")
            create(IntelliJPlatformType.RustRover, "2026.2")
        }
    }
}
