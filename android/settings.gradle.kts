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
        // Mapbox: token mora imati scope DOWNLOADS:READ.
        // Stavi ga u local.properties (kljuc MAPBOX_DOWNLOADS_TOKEN) - ne commituj ga.
        val props = java.util.Properties().apply {
            val f = rootDir.resolve("local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        val mapboxToken = props.getProperty("MAPBOX_DOWNLOADS_TOKEN")
            ?: System.getenv("MAPBOX_DOWNLOADS_TOKEN")
            ?: ""
        if (mapboxToken.isNotBlank()) {
            maven {
                url = uri("https://api.mapbox.com/downloads/v2/releases/maven")
                authentication {
                    create<org.gradle.api.authentication.BasicAuthentication>("basic")
                }
                credentials {
                    username = "mapbox"
                    password = mapboxToken
                }
            }
        }
    }
}

rootProject.name = "Radari CG"
include(":app")
