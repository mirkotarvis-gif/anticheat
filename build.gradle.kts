plugins {
    java
}

group = "dev.antiesp"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-releases/")
    maven("https://repo.codemc.io/repository/maven-snapshots/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    // Provided at runtime by the PacketEvents plugin you already have installed.
    compileOnly("com.github.retrooper:packetevents-spigot:2.11.2")
}

java {
    // Built with a Java 25 JDK.
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // Bytecode level 21 = Paper 1.21.11's minimum. Runs fine on Java 25.
    // Change to 25 if you want to require Java 25 on the server.
    options.release.set(21)
}

tasks.processResources {
    filesMatching("plugin.yml") { expand("version" to project.version) }
}
