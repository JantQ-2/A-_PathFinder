import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  kotlin("jvm")
  id("fabric-loom")
  `maven-publish`
  java
}

val baseGroup: String by project
val lwjglVersion: String by project
val addonVersion: String by project
val addonName: String by project

base {
  archivesName = addonName
  version = addonVersion
  group = baseGroup
}

repositories {
  mavenCentral()
  maven("https://maven.meteordev.org/releases")
  maven("https://pkgs.dev.azure.com/djtheredstoner/DevAuth/_packaging/public/maven/v1")
  maven("https://maven.quiteboring.dev/")
}

dependencies {
  minecraft("com.mojang:minecraft:${property("minecraft_version")}")
  mappings("net.fabricmc:yarn:${property("yarn_mappings")}:v2")
  modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")

  modImplementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")
  modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_version")}")

  modImplementation("org.cobalt:cobalt:1.0.0")
  modImplementation("meteordevelopment:discord-ipc:1.1")
  modImplementation("org.reflections:reflections:0.10.2")

  modImplementation("org.lwjgl:lwjgl-nanovg:${lwjglVersion}")
  modImplementation("org.lwjgl:lwjgl-nanovg:${lwjglVersion}:natives-windows")
  modImplementation("org.lwjgl:lwjgl-nanovg:${lwjglVersion}:natives-linux")
  modImplementation("org.lwjgl:lwjgl-nanovg:${lwjglVersion}:natives-macos")
  modImplementation("org.lwjgl:lwjgl-nanovg:${lwjglVersion}:natives-macos-arm64")

  modRuntimeOnly("me.djtheredstoner:DevAuth-fabric:1.2.1")
}

tasks {
  processResources {
    inputs.property("version", project.version)

    filesMatching("fabric.mod.json") {
      expand(getProperties())
      expand(mutableMapOf("version" to project.version))
    }
  }

  publishing {
    publications {
      create<MavenPublication>("mavenJava") {
        groupId = "jant.path"
        artifactId = addonName
        version = addonVersion
        
        artifact(remapJar) {
          builtBy(remapJar)
        }

        artifact(kotlinSourcesJar) {
          builtBy(remapSourcesJar)
        }
        
        pom {
          name.set("A# Pathfinding API")
          description.set("Advanced A* pathfinding API for Minecraft Fabric mods")
          url.set("https://github.com/CobaltScripts/Cobalt")
          
          licenses {
            license {
              name.set("CC0-1.0")
              url.set("https://creativecommons.org/publicdomain/zero/1.0/")
            }
          }
          
          developers {
            developer {
              name.set("A# Pathfinding Contributors")
            }
          }
        }
      }
    }
    
    repositories {
      // Publish to local maven for testing
      mavenLocal()
      
      // TODO: Configure your maven repository for publishing
      // maven {
      //   name = "YourRepo"
      //   url = uri("https://your-maven-repo.com/releases")
      //   credentials {
      //     username = project.findProperty("repoUsername") as String? ?: System.getenv("REPO_USERNAME")
      //     password = project.findProperty("repoPassword") as String? ?: System.getenv("REPO_PASSWORD")
      //   }
      // }
    }
  }

  compileKotlin {
    compilerOptions {
      jvmTarget = JvmTarget.JVM_21
    }
  }
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(21))
  }
}
