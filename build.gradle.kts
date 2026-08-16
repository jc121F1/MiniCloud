import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    java
    idea
    jacoco
    id("com.gradleup.shadow") version "9.1.0"
}

group = "jc121f1"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation("io.javalin:javalin:7.2.3")
    implementation("com.google.dagger:dagger:2.51.1")
    implementation("org.slf4j:slf4j-api:2.0.17")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core:5.19.0")
    testImplementation("org.assertj:assertj-core:3.27.7")

    annotationProcessor("com.google.dagger:dagger-compiler:2.51.1")
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<ShadowJar>("shadowJar") {
    manifest {
        attributes["Main-Class"] = "jc121f1.Main"
    }
}