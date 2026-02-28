plugins {
    java
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
}

group = "com.vnc"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.bytedeco:javacv-platform:1.5.11")
}

tasks.register<Exec>("generateKeystore") {
    val keystoreFile = file("src/main/resources/keystore.p12")
    outputs.file(keystoreFile)
    onlyIf { !keystoreFile.exists() }
    commandLine(
        "keytool", "-genkeypair",
        "-alias", "vnc",
        "-keyalg", "RSA",
        "-keysize", "2048",
        "-storetype", "PKCS12",
        "-keystore", keystoreFile.absolutePath,
        "-validity", "3650",
        "-storepass", "changeit",
        "-dname", "CN=localhost,OU=VNC,O=Dev,L=Local,ST=Dev,C=US"
    )
}

tasks.named("processResources") {
    dependsOn("generateKeystore")
}
