import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.publish.maven.MavenPublication
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "9.3.0"
    jacoco
    `maven-publish`
}

group = "ru.ruscrafting"
version = providers.gradleProperty("pluginVersion").get()
description = "Standalone mount collection plugin for RusCrafting"

jacoco {
    toolVersion = "0.8.15"
}

allprojects {
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

kotlin {
    jvmToolchain(25)
}

val integrationTestSourceSet = sourceSets.create("integrationTest") {
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
}
kotlin.target.compilations.getByName("integrationTest")
    .associateWith(kotlin.target.compilations.getByName("main"))

dependencies {
    implementation(kotlin("stdlib"))
    implementation("ru.ruscrafting.arc:arc-core:2.7.38")
    implementation("ru.ruscrafting.arc:arc-core-logging:2.7.38")
    implementation("ru.ruscrafting.arc:arc-core-menu:2.7.38")
    implementation("ru.ruscrafting.arc:arc-core-paper:2.7.38")
    implementation("ru.ruscrafting.arc:arc-core-paper-menu:2.7.38")
    implementation("ru.ruscrafting.arc:arc-core-redis:2.7.38")
    implementation("ru.ruscrafting.arc:arc-core-sql:2.7.38")

    compileOnly(project(":arc-mounts-api"))
    compileOnly("ru.ruscrafting.arc:arc-core-paper-api:2.7.38")
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")
    compileOnly("net.citizensnpcs:citizens-main:2.0.42-SNAPSHOT") {
        exclude(group = "*", module = "*")
    }
    compileOnly("net.luckperms:api:5.4")
    compileOnly("com.github.retrooper:packetevents-spigot:2.12.1") {
        exclude(group = "io.netty")
    }

    testImplementation("ru.ruscrafting.arc:arc-core-paper-testing:2.7.38")
    testImplementation(project(":arc-mounts-api"))
    testImplementation("ru.ruscrafting.arc:arc-core-paper-api:2.7.38")
    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testImplementation("io.kotest:kotest-runner-junit5:6.0.7")
    testImplementation("io.kotest:kotest-assertions-core:6.0.7")
    testImplementation("io.mockk:mockk:1.14.7")
    testImplementation("net.citizensnpcs:citizens-main:2.0.42-SNAPSHOT") {
        exclude(group = "*", module = "*")
    }
    testImplementation("net.luckperms:api:5.4")
    testImplementation("com.github.retrooper:packetevents-spigot:2.12.1") {
        exclude(group = "io.netty")
    }
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")

    "integrationTestImplementation"(sourceSets.test.get().output)
    "integrationTestImplementation"("ru.ruscrafting.arc:arc-core-integration-testing:2.7.38")
    configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])
    configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
        allWarningsAsErrors.set(true)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("-Djdk.attach.allowAttachSelf=true", "-XX:+EnableDynamicAgentLoading")
    forkEvery = 10
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

tasks.register<Test>("integrationTest") {
    description = "Runs Redis and SQL integration tests against disposable services."
    group = "verification"
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    shouldRunAfter(tasks.test)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.processResources {
    inputs.property("pluginVersion", project.version)
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    archiveClassifier.set("plain")
}

tasks.shadowJar {
    archiveBaseName.set("ArcMounts")
    archiveClassifier.set("")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    mergeServiceFiles()

    // ARC owns these shared service contracts and provider implementations.
    exclude("ru/arc/mounts/api/**")
    exclude("ru/arc/paper/api/**")
    exclude("ru/arc/paper/packet/PaperVisualPacketRuntime*.class")
    exclude("ru/arc/paper/sql/PaperSqlServiceProvider*.class")
    exclude("ru/arc/paper/sql/SharedPaperSqlService*.class")
    exclude("ru/arc/paper/redis/PaperRedisServiceProvider*.class")
    exclude("ru/arc/paper/redis/SharedPaperRedisService*.class")
    exclude("ru/arc/paper/metrics/PaperArcMetricsProvider*.class")
    exclude("ru/arc/paper/runtime/PaperNativeCleanupServiceProvider*.class")
    exclude("org/bukkit/**")
    exclude("io/papermc/**")
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

publishing {
    repositories {
        maven {
            name = "staging"
            url = uri(layout.buildDirectory.dir("release-repository"))
        }
    }
    publications {
        create<MavenPublication>("runtimePlugin") {
            groupId = "ru.ruscrafting"
            artifactId = "ArcMounts"
            version = project.version.toString()
            artifact(tasks.shadowJar)
            pom {
                name.set("ArcMounts")
                description.set("Standalone mount collection plugin for RusCrafting")
                url.set("https://github.com/alexey-va/ArcMounts")
                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
            }
        }
    }
}
