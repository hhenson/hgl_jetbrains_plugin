import java.time.Duration
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.changelog") version "2.5.0"
    id("org.jetbrains.intellij.platform") version "2.19.0"
    id("org.jetbrains.intellij.platform.grammarkit") version "2.19.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

val signingKey = providers.environmentVariable("PRIVATE_KEY").filter { it.isNotBlank() }
val signingCertificate = providers.environmentVariable("CERTIFICATE_CHAIN").filter { it.isNotBlank() }
val signingPassword = providers.environmentVariable("PRIVATE_KEY_PASSWORD").filter { it.isNotEmpty() }
require(signingKey.isPresent == signingCertificate.isPresent) {
    "Set both PRIVATE_KEY and CERTIFICATE_CHAIN, or omit both to publish without author signing."
}
val authorSigning = signingKey.isPresent
require(!signingPassword.isPresent || authorSigning) {
    "PRIVATE_KEY_PASSWORD requires PRIVATE_KEY and CERTIFICATE_CHAIN."
}

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
        bundledModule("intellij.platform.debugger")
        bundledModule("intellij.platform.dap")
        bundledPlugin("com.intellij.platform.testRunner")
        bundledPlugin("com.intellij.clion-compdb")
        testFramework(TestFrameworkType.Platform)
        pluginVerifier("1.411")
        zipSigner()
    }
    implementation("org.commonmark:commonmark:0.33.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.33.0")
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
        changeNotes = providers.environmentVariable("RELEASE_NOTES").filter { it.isNotBlank() }.map {
            markdownToHTML(it)
        }
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }
    pluginVerification {
        ides {
            create(IntelliJPlatformType.CLion, providers.gradleProperty("platformVersion").get())
            create(IntelliJPlatformType.RustRover, providers.gradleProperty("rustRoverVersion").get())
        }
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN").filter { it.isNotBlank() }
        channels = listOf("default")
    }
    signing {
        privateKey = signingKey
        certificateChain = signingCertificate
        password = signingPassword
    }
}

// The same editor suite runs in each product's headless platform fixture.
intellijPlatformTesting {
    testIde {
        register("testRustRover") {
            type = IntelliJPlatformType.RustRover
            version = providers.gradleProperty("rustRoverVersion")
            testFramework(TestFrameworkType.Platform)
        }
    }
}

// CLion's C++ backend can outlive a headless fixture and access its deleted project.
tasks.test {
    systemProperty("rider.disable.resharper.backend", "true")
}

// A parser regression must fail CI instead of leaving an editor test spinning.
tasks.withType<Test>().configureEach {
    // Bundled LLDB imports Python modules inside the read-only IDE distribution.
    environment("PYTHONDONTWRITEBYTECODE", "1")
    inputs.dir("src/test/testData").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file("spec/hgl_spec/spec/language/source-text.md").withPathSensitivity(PathSensitivity.RELATIVE)
    val workspace = providers.gradleProperty("hglBenchmarkWorkspace")
    if (workspace.isPresent) {
        systemProperty("hglBenchmarkWorkspace", file(workspace.get()).absolutePath)
        outputs.upToDateWhen { false }
    } else exclude("**/HglPerformanceBaselineTest.class")
    if (providers.environmentVariable("HGL_TEST_CLI").isPresent) {
        outputs.upToDateWhen { false }
    } else {
        exclude("**/HglCompilerRunTest.class", "**/HglCheckOnSaveTest.class",
            "**/HglDebugApplicationTest.class", "**/HglDebugTestSuiteTest.class")
    }
    timeout = Duration.ofMinutes(5)
}

// Select by configuration, including when signPlugin is already up to date.
val marketplaceArchive = if (authorSigning) tasks.signPlugin.flatMap { it.signedArchiveFile }
    else tasks.buildPlugin.flatMap { it.archiveFile }
tasks.verifyPlugin {
    archiveFile = marketplaceArchive
}
tasks.verifyPluginSignature {
    dependsOn(tasks.signPlugin)
}
val readyArchive = layout.buildDirectory.file("marketplace/${rootProject.name}-${version}.zip")
val prepareMarketplaceRelease by tasks.registering(Sync::class) {
    group = "publishing"
    description = "Tests and verifies the exact ZIP prepared for Marketplace."
    dependsOn(tasks.test, tasks.named("testRustRover"), tasks.verifyPlugin)
    if (authorSigning) dependsOn(tasks.verifyPluginSignature)
    from(marketplaceArchive) { rename { readyArchive.get().asFile.name } }
    into(readyArchive.map { it.asFile.parentFile })
}
tasks.publishPlugin {
    dependsOn(prepareMarketplaceRelease)
    archiveFiles.setFrom(readyArchive)
}
