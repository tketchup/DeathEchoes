plugins {
    java
}

group = "dev.deathechoes"
version = "1.0.0"
description = "Geister-Replays der letzten Sekunden vor dem Tod, gekoppelt an das Grab"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
}

dependencies {
    // Seit 26.1 nutzt Paper das Versionsschema <MC-Version>.build.<Build>[-stable] statt -R0.1-SNAPSHOT.
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")

    testImplementation("io.papermc.paper:paper-api:26.3.build.+")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    // Paper 26.x setzt Java 25 voraus.
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
}

tasks.processResources {
    val props = mapOf("version" to project.version, "description" to project.description)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.test {
    useJUnitPlatform()
}

// Fester Dateiname: so landet beim Deploy keine zweite Jar mit anderer Version im plugins-Ordner.
tasks.jar {
    archiveFileName.set("DeathEchoes.jar")
}

// Kopiert die Plugin-Jar auf den lokalen Testserver (gemeinsam für mehrere Plugins genutzt).
tasks.register<Copy>("deploy") {
    dependsOn(tasks.jar)
    from(tasks.jar)
    into(System.getProperty("user.home") + "/Documents/code/testserver/plugins")
}
