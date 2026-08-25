plugins {
    id("java")
    id("me.champeau.jmh") version "0.7.3"
    id("org.xbib.gradle.plugin.jflex") version "3.0.2"
}

version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
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

jmh {
    includes.set(listOf("PositionBenchmark"))
    resultFormat.set("CSV")
    resultsFile.set(layout.projectDirectory.file("benchmarks/positions.csv"))
}

tasks.register<JavaExec>("jmhTail") {
    group = "benchmark"
    dependsOn("jmhJar")
    classpath(files(tasks.named<Jar>("jmhJar").flatMap { it.archiveFile }))
    mainClass.set("org.openjdk.jmh.Main")
    args(
        "TailBenchmark",
        "-rf", "CSV",
        "-rff", layout.projectDirectory.file("benchmarks/tail.csv").asFile.absolutePath,
    )
}
