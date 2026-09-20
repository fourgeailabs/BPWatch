pluginManagement {
    // The Gradle Plugin Portal currently 303-redirects the Android and
    // Compose-compiler plugin markers to Maven Central, where they don't
    // exist. Map those plugin IDs straight at their implementation modules.
    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                "com.android.application", "com.android.library" ->
                    useModule("com.android.tools.build:gradle:8.5.2")
            }
        }
    }
    repositories {
        // Local proxy (http://127.0.0.1:8081) forwards to Google/Maven Central/
        // Plugin Portal, bypassing the sandbox MITM TLS issues in Gradle's HTTP client.
        maven {
            url = uri("http://127.0.0.1:8081/")
            isAllowInsecureProtocol = true
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            url = uri("http://127.0.0.1:8081/")
            isAllowInsecureProtocol = true
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "BPWatch"
include(":mobile")
include(":wear")
