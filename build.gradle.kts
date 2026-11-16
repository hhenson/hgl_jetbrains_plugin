import java.time.Duration
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
        // Compile against CLion; test and verify the same plugin on RustRover.
        // Use -PplatformLocalPath=<CLion installation> to avoid downloading it.
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
        targetRootOutputDir = file("src/main/gen")
        pathToClass = "io/github/hhenson/hgl/lexer/_HglLexer.java"
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
            create(IntelliJPlatformType.RustRover, "2026.2")
        }
    }
}

// The same editor suite runs in each product's headless platform fixture.
intellijPlatformTesting {
    testIde {
        register("testRustRover") {
            type = IntelliJPlatformType.RustRover
            version = providers.gradleProperty("platformVersion")
            testFramework(TestFrameworkType.Platform)
        }
    }
}

// A parser regression must fail CI instead of leaving an editor test spinning.
tasks.withType<Test>().configureEach {
    timeout = Duration.ofMinutes(5)
}
