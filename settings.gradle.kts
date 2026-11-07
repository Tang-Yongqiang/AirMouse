pluginManagement {
    repositories {
        // 本地补齐仓库：存放缓存里缺失的构件（离线环境自救用）
        maven {
            url = uri("local-repo")
            metadataSources { mavenPom(); artifact() }
        }
        // 本机 Gradle 缓存的产物来自这些仓库（离线构建必须声明同名仓库才能命中缓存）
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            url = uri("local-repo")
            metadataSources { mavenPom(); artifact() }
        }
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "AirMouse"
include(":app")
