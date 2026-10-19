pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Repository cho Eclipse Paho MQTT
        maven {
            url = uri("https://repo.eclipse.org/content/repositories/paho-releases/")
        }
    }
}

rootProject.name = "IOT2"
include(":app")