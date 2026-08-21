import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    java
    idea
    jacoco
    checkstyle
    id("io.freefair.lombok") version "9.5.0"
    id("com.gradleup.shadow") version "9.1.0"
    id("com.github.spotbugs") version "6.4.2"
}

System.setProperty("DEBUG_APP", "true")

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

spotbugs {
    ignoreFailures = false
}

checkstyle {
    toolVersion = "10.26.1"
    isIgnoreFailures = false
}

val mockitoAgent = configurations.create("mockitoAgent")
dependencies {
    implementation("io.javalin:javalin:7.2.3")
    implementation("com.google.dagger:dagger:2.60.1")
    implementation("org.slf4j:slf4j-api:2.0.17")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.1")
    implementation("com.github.docker-java:docker-java:3.7.1")
    implementation("org.jmdns:jmdns:3.6.3")
    implementation("com.github.docker-java:docker-java-transport-httpclient5:3.7.1")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("org.mockito:mockito-junit-jupiter:5.12.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.javalin:javalin-testtools:7.2.3")
    testImplementation("org.awaitility:awaitility:4.3.0")
    mockitoAgent("org.mockito:mockito-core:5.19.0")  { isTransitive = false }

    annotationProcessor("com.google.dagger:dagger-compiler:2.60.1")
    testAnnotationProcessor("com.google.dagger:dagger-compiler:2.57.2")
}
tasks {
    test {
        jvmArgs.add("-javaagent:${mockitoAgent.asPath}")
        exclude("**/*EndToEndTest.class")
        useJUnitPlatform()
        systemProperty("DISABLE_JMDNS", "true")
    }
}

tasks.named<ShadowJar>("shadowJar") {
    manifest {
        attributes["Main-Class"] = "jc121f1.Main"
    }
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        html.required.set(true)
        xml.required.set(true)
        csv.required.set(false)
    }
}

tasks.build {
    dependsOn(tasks.jacocoTestReport)
    dependsOn(tasks.shadowJar)
    doLast {
        val report = tasks.jacocoTestReport
            .get()
            .reports
            .html
            .outputLocation
            .get()
            .asFile
            .resolve("index.html")

        println()
        println("JaCoCo coverage report:")
        println(report.toURI())
    }
}

tasks.named<Checkstyle>("checkstyleMain") {
    configFile = file("config/checkstyle/checkstyleMain.xml")
}

tasks.named<Checkstyle>("checkstyleTest") {
    configFile = file("config/checkstyle/checkstyleTest.xml")
}

tasks.named<com.github.spotbugs.snom.SpotBugsTask>("spotbugsTest") {
    enabled = false
}