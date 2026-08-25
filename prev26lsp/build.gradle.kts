plugins {
    id("java")
    id("me.champeau.jmh") version "0.7.3"
    id("org.xbib.gradle.plugin.jflex") version "3.0.2"
    id("com.gradleup.shadow") version "9.4.2"
}

version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:1.0.0")
    implementation("it.unimi.dsi:fastutil:8.5.18")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

configure<org.xbib.gradle.plugin.jflex.JFlexExtension> {
    writeIntoJavaSrc = true
}

tasks.withType<Jar>().configureEach  {
    manifest {
        attributes["Main-Class"] = "prev26lsp.ServerLauncher"
    }
}

jmh {
    includes.set(listOf("SemanticUpdateBenchmark"))
    resultFormat.set("JSON")
    resultsFile.set(layout.projectDirectory.file("benchmarks/semantic-update-jmh.json"))
}
