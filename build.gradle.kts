plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "io.github.gazehighlighter"
version = file("VERSION").readText().trim()

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        val localIde = providers.gradleProperty("localIdePath").orNull
        if (localIde != null) {
            local(localIde)
            bundledPlugin("com.intellij.modules.jcef")
        }
        else intellijIdeaCommunity(providers.gradleProperty("platformVersion").getOrElse("2023.1"))
    }
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    // Use the JVM running Gradle; emit Java 17 bytecode even when building with a newer JBR.
    toolchain.languageVersion.set(JavaLanguageVersion.of(JavaVersion.current().majorVersion.toInt()))
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
tasks.test { useJUnitPlatform() }
tasks.processResources {
    from("standalone/public/vendor/runtime") { into("gaze/web/runtime") }
}
tasks.register<JavaExec>("previewResearchPanel") {
    group = "verification"
    description = "Render the native research panel with synthetic state, without starting an IDE"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.gazehighlighter.GazeResearchPanelPreview")
    systemProperty("java.awt.headless", "true")
}

intellijPlatform {
    pluginConfiguration {
        version = project.version.toString()
        ideaVersion { sinceBuild = "231"; untilBuild = provider { null } }
    }
    buildSearchableOptions = false
    instrumentCode = false
}

val generatedVersion = layout.buildDirectory.dir("generated/version")
val generateVersion by tasks.registering {
    inputs.file("VERSION")
    outputs.dir(generatedVersion)
    doLast {
        val target = generatedVersion.get().file("io/github/gazehighlighter/GazeVersion.java").asFile
        target.parentFile.mkdirs()
        target.writeText("package io.github.gazehighlighter; final class GazeVersion { static final String VERSION = \"${project.version}\"; }")
    }
}
sourceSets.main { java.srcDir(generatedVersion) }
tasks.compileJava { dependsOn(generateVersion) }
intellijPlatform {
    pluginVerification {
        ides {
            val localIde = providers.gradleProperty("localIdePath").orNull
            if (localIde != null) local(localIde)
            else { create("IC", "2023.1"); create("IC", "2025.2") }
        }
    }
}
