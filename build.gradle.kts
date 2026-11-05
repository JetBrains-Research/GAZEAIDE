plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "io.github.gazehighlighter"
version = "1.0.0"

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
