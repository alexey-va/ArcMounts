import org.gradle.api.publish.maven.MavenPublication

plugins {
    `java-library`
    `maven-publish`
}

group = "ru.ruscrafting.arc"
version = providers.gradleProperty("mountsApiVersion").get()
description = "Public Java API for the ArcMounts Paper plugin"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
}

dependencies {
    // Bukkit types in the API are supplied by Paper and are not a Maven runtime dependency.
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
}

publishing {
    repositories {
        maven {
            name = "staging"
            url = uri(rootProject.layout.buildDirectory.dir("release-repository"))
        }
    }
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "arc-mounts-api"
            pom {
                name.set("ArcMounts API")
                description.set("Public Java-shaped service contract for ArcMounts")
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
