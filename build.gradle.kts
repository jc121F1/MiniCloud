import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.github.spotbugs.snom.SpotBugsTask

plugins {
    java
    idea
    jacoco
    checkstyle
    id("io.freefair.lombok") version "9.5.0"
    id("com.gradleup.shadow") version "9.6.1"
    id("com.github.spotbugs") version "6.5.11"
    id("org.openapi.generator") version "7.25.0"
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
    val openapi = "7.2.3"

    implementation("io.javalin:javalin:7.2.3")
    implementation("io.javalin.community.openapi:javalin-openapi-plugin:${openapi}")
    implementation("io.javalin.community.openapi:javalin-swagger-plugin:${openapi}")
    implementation("io.javalin.community.openapi:javalin-redoc-plugin:${openapi}")
    implementation("com.google.dagger:dagger:2.60.1")
    implementation("org.slf4j:slf4j-api:2.0.19")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.22.2")
    implementation("com.github.docker-java:docker-java:3.7.1")
    implementation("org.jmdns:jmdns:3.6.3")
    implementation("com.github.docker-java:docker-java-transport-httpclient5:3.7.1")
    implementation(platform("software.amazon.awssdk:bom:2.54.+"))
    implementation("software.amazon.awssdk:dynamodb")
    implementation("software.amazon.awssdk:dynamodb-enhanced")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.85")
    implementation(platform("io.netty:netty-bom:4.2.17.Final"))
    runtimeOnly("ch.qos.logback:logback-classic:1.6.3")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("org.mockito:mockito-junit-jupiter:5.23.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.javalin:javalin-testtools:7.2.3")
    testImplementation("org.awaitility:awaitility:4.3.0")
    mockitoAgent("org.mockito:mockito-core:5.23.0")  { isTransitive = false }

    annotationProcessor("com.google.dagger:dagger-compiler:2.60.1")
    annotationProcessor("io.javalin.community.openapi:openapi-annotation-processor:$openapi")
    testAnnotationProcessor("com.google.dagger:dagger-compiler:2.60.1")
}
tasks {
    test {
        jvmArgs.add("-javaagent:${mockitoAgent.asPath}")
        exclude("**/*EndToEndTest.class")
        useJUnitPlatform()
        systemProperty("DISABLE_JMDNS", "true")
    }
}

sourceSets {
    main {
        java {
            srcDir(
                layout.buildDirectory
                    .dir("generated/openapi-client/src/main/java")
            )
        }
    }
}

val openApiSpecClasses = layout.buildDirectory.dir(
    "openapi-spec/classes"
)

val openApiSpecFile = openApiSpecClasses.map {
    it.file("openapi-plugin/openapi-default.json")
}

val generateOpenApiSpec = tasks.register<JavaCompile>("generateOpenApiSpec") {
    description = "Generates the OpenAPI specification using the project's annotation processors."

    source = sourceSets.main.get().java

    classpath = sourceSets.main.get().compileClasspath

    destinationDirectory.set(openApiSpecClasses)

    options.annotationProcessorPath = configurations.annotationProcessor.get()

    options.compilerArgs.add("-proc:only")

    options.release.set(25)
}

tasks.openApiGenerate {
    dependsOn(generateOpenApiSpec)

    generatorName.set("java")

    inputSpec.set(openApiSpecFile)

    outputDir.set(
        layout.buildDirectory.dir("generated/openapi-client")
    )

    apiPackage.set("jc121f1.minicloud.client.api")
    modelPackage.set("jc121f1.minicloud.client.model")
    invokerPackage.set("jc121f1.minicloud.client")

    configOptions.put("library", "native")
    configOptions.put("dateLibrary", "java8")
    configOptions.put("serializationLibrary", "jackson")
    configOptions.put("useJakartaEe", "true")
    configOptions.put("openApiNullable", "false")

    generateApiTests.set(false)
    generateModelTests.set(false)
    generateApiDocumentation.set(true)
    generateModelDocumentation.set(true)

    cleanupOutput.set(true)
}

tasks.named<JavaCompile>("compileJava") {
    dependsOn(tasks.openApiGenerate)
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
        println(report.toURI())
        println(
            "OpenAPI client generated at: " +
                    layout.buildDirectory
                        .dir("generated/openapi-client")
                        .get()
                        .asFile
                        .absolutePath
        )
    }
}

tasks.named<Checkstyle>("checkstyleMain") {
    configFile = file("config/checkstyle/checkstyleMain.xml")
}

tasks.named<Checkstyle>("checkstyleTest") {
    configFile = file("config/checkstyle/checkstyleTest.xml")
    exclude("**/generated/**")
}

tasks.named<SpotBugsTask>("spotbugsMain") {
    excludeFilter.set(
        file("$projectDir/config/spotbugs/spotbugs-exclude.xml")
    )
}

tasks.named<SpotBugsTask>("spotbugsTest") {
    enabled = false
}