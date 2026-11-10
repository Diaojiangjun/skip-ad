pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // repo1.maven.org is unreachable in this environment; repo.maven.apache.org is the same CDN.
        maven("https://repo.maven.apache.org/maven2")
        maven("https://maven.aliyun.com/repository/public")
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://repo.maven.apache.org/maven2")
        maven("https://maven.aliyun.com/repository/public")
    }
}

rootProject.name = "SkipAd"
include(":app")
