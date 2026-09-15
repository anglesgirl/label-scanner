pluginManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://jitpack.io") }
        // AndroidUSBCamera 修复版（移除失效的 com.serenegiant:common 传递依赖及
        // 其中旧版 USBMonitor.class，避免与项目源码版重复）：见 app/libs/
        flatDir { dirs("app/libs") }
    }
}
rootProject.name = "LabelScanner"
include(":app")
