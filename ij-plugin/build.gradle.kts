import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20"
    id("org.jetbrains.intellij.platform")
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
    id("org.jetbrains.kotlinx.kover") version "0.9.11"
    id("org.jetbrains.changelog") version "2.5.0"
}

group = "com.halotukozak.alpaca.plugin"

// kotlin-stdlib (and its transitive `annotations:13.0`) ship with the IntelliJ Platform,
// which also provides them on the compile classpath. Bundling our own copy in the plugin
// zip is redundant and the Plugin Verifier flags a bundled stdlib.
// `kotlin.stdlib.default.dependency=false` stops the Kotlin plugin adding one; this drops
// the copy kotlinx-serialization pulls in transitively from the packaged runtime classpath
// only (not the platform's own resolution, which genuinely needs it).
configurations.runtimeClasspath {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        intellijIdea("2025.2.6.2")
        testFramework(TestFrameworkType.Platform)
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Kotlin's default ("enable") emits a compatibility bridge in every implementing class for
        // each default method of an implemented interface -- including ones we never touch. Several
        // IntelliJ Platform interfaces (e.g. ToolWindowFactory) have default methods marked
        // @ApiStatus.Internal; those bridges alone trip the Plugin Verifier's INTERNAL_API_USAGES
        // check even though our code never calls or overrides them. This plugin has no Kotlin
        // caller-compatibility surface to preserve, so no-compatibility (skip the bridges) is safe.
        jvmDefault = JvmDefaultMode.NO_COMPATIBILITY
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.halotukozak.alpaca.plugin"
        name = "Alpaca"
        version = project.version.toString()

        ideaVersion {
            // 252 == 2025.2, the platform this plugin builds against. The plugin only
            // touches stable `com.intellij.modules.platform` API, so it is not pinned
            // to an upper IDE build -- leave untilBuild open-ended.
            sinceBuild = "252"
            untilBuild = provider { null }
        }

        // changeNotes is wired automatically from CHANGELOG.md by the changelog plugin:
        // the section matching `version` above, or the "[Unreleased]" section before a
        // release has been cut.
    }

    // `signPlugin` and `publishPlugin` read their credentials from the environment:
    //   PUBLISH_TOKEN        - JetBrains Marketplace token (Marketplace > My Tokens)
    //   CERTIFICATE_CHAIN    - PEM chain for the signing certificate
    //   PRIVATE_KEY          - PEM private key matching the chain
    //   PRIVATE_KEY_PASSWORD - passphrase for PRIVATE_KEY
    // See https://plugins.jetbrains.com/docs/intellij/plugin-signing.html
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // Version suffix -> release channel: `0.1.0` publishes to the stable channel,
        // `0.2.0-eap.1` / `0.2.0-beta` publish to a matching pre-release channel that
        // users must opt into by adding a custom plugin repository URL.
        val channel =
            project.version
                .toString()
                .substringAfter('-', "")
                .substringBefore('.')
                .ifEmpty { "default" }
        channels = listOf(channel)
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks.test {
    // The tests drive real grammars that the alpaca build exports into this directory (see
    // .github/workflows/ij-plugin-test.yml). Declaring it as an input keeps the Gradle build cache
    // from replaying old results after the library changes what it exports.
    inputs
        .dir("/tmp/alpaca-grammar-export")
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .optional()
}

changelog {
    // Powers the "compare" links patchChangelog writes into CHANGELOG.md. Tags in this
    // repo are the Scala library's (`v*`); the plugin has no tags of its own, so these
    // links point at the repo tree rather than plugin-specific diffs.
    repositoryUrl = "https://github.com/halotukozak-com/alpaca"
}
