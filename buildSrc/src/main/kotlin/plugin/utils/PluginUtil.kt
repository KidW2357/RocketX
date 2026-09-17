package plugin.utils

import com.android.build.api.transform.Transform
import com.android.build.gradle.AppExtension
import com.android.build.gradle.BaseExtension
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.plugins.ide.idea.IdeaPlugin
import org.gradle.plugins.ide.idea.model.IdeaModule
import plugin.RocketXPlugin
import java.io.File
import java.util.*
import kotlin.reflect.jvm.isAccessible

/**
 * description:
 * author chaojiong.zhang
 * data: 2021/11/2
 * copyright TCL+
 */


//判断是否子 project 的
fun hasAndroidPlugin(curProject: Project): Boolean {
    return curProject.plugins.hasPlugin("com.android.library")
}

//判断是否子 project 的
fun hasAppPlugin(curProject: Project): Boolean {
    return curProject.plugins.hasPlugin("com.android.application")
}

//判断是否java project 的
fun hasJavaPlugin(curProject: Project): Boolean {
    return curProject.plugins.hasPlugin("java-library")
}


fun isRunAssembleTask(curProject: Project): Boolean {
    return requestedTaskNames(curProject).any { isAssembleTaskForProject(it, curProject) } &&
        curProject.projectDir.absolutePath == curProject.gradle.startParameter.currentDir.absolutePath
}


fun isEnable(curProject: Project): Boolean {
    val propertyValue = curProject.findProperty("rocketx.enabled")?.toString()
    if (propertyValue != null) {
        return propertyValue.toBoolean()
    }
    val enableFile = File(curProject.rootProject.rootDir.absolutePath + File.separator + ".gradle" + File.separator + "rocketXEnable")
    return enableFile.exists()
}

fun validateBuildEnvironment(appProject: Project) {
    val configurationCacheEnabled =
        appProject.findProperty("org.gradle.configuration-cache")?.toString()?.toBoolean() == true
    if (configurationCacheEnabled) {
        throw GradleException(
            "RocketX dev_zy is not compatible with Gradle configuration cache. " +
                "Disable org.gradle.configuration-cache while RocketX is enabled."
        )
    }
    if (appProject.gradle.startParameter.isConfigureOnDemand) {
        throw GradleException(
            "RocketX dev_zy requires configuration on demand to be disabled. " +
                "Disable org.gradle.configureondemand while RocketX is enabled."
        )
    }
}

private fun requestedTaskNames(project: Project): List<String> {
    val taskNames = project.gradle.startParameter.taskNames
    if (taskNames.isNotEmpty()) return taskNames
    return project.gradle.startParameter.taskRequests.flatMap { it.args }
}

private fun isAssembleTaskForProject(taskName: String, appProject: Project): Boolean {
    val simpleTaskName = taskName.substringAfterLast(':')
    if (!simpleTaskName.startsWith(RocketXPlugin.ASSEMBLE, ignoreCase = true)) return false
    if (!taskName.contains(':')) return true
    return taskName.substringBeforeLast(':') == appProject.path
}

//通过 startParameter 获取  FlavorBuildType
fun getFlavorBuildType(appProject: Project): String {
    val taskName = requestedTaskNames(appProject)
        .firstOrNull { isAssembleTaskForProject(it, appProject) }
        ?.substringAfterLast(':')
        .orEmpty()
    var flavorBuildType = taskName.substring(RocketXPlugin.ASSEMBLE.length)
    if (flavorBuildType.isNotEmpty()) {
        flavorBuildType = flavorBuildType.substring(0, 1).toLowerCase(Locale.ROOT) + flavorBuildType.substring(1)
    }
    return flavorBuildType
}

//不能通过name ，需要通过 path ，有可能有多级目录(: 作为aar名字会有冲突不能用)
fun getFlatAarName(project: Project): String {
    return project.path.substring(1).replace(":", "-")
}

fun isCurProjectRun(appProject: Project): Boolean {
    return requestedTaskNames(appProject).any { isAssembleTaskForProject(it, appProject) }
}


fun boostGradleOption(appProject: Project) {
    //并行运行task

    if (!appProject.hasProperty("org.gradle.daemon")) {
        appProject.rootProject.extensions.extraProperties.set("org.gradle.daemon", "true")
    }
    if (!appProject.hasProperty("kotlin.incremental")) {
        appProject.rootProject.extensions.extraProperties.set("kotlin.incremental", true)
    }
    if (!appProject.hasProperty("kotlin.incremental.java")) {
        appProject.rootProject.extensions.extraProperties.set("kotlin.incremental.java", "true")
    }
    if (!appProject.hasProperty("kotlin.incremental.js")) {
        appProject.rootProject.extensions.extraProperties.set("kotlin.incremental.js", "true")
    }
    if (!appProject.hasProperty("kotlin.caching.enabled")) {
        appProject.rootProject.extensions.extraProperties.set("kotlin.caching.enabled", "true")
    }

    if (!appProject.hasProperty("org.gradle.parallel")) {
        appProject.rootProject.extensions.extraProperties.set("org.gradle.parallel", "true")
    }
    if (!appProject.hasProperty("kotlin.parallel.tasks.in.project")) {
        appProject.rootProject.extensions.extraProperties.set("kotlin.parallel.tasks.in.project", "true")
    }
    if (!appProject.hasProperty("kapt.use.worker.api")) {
        appProject.rootProject.extensions.extraProperties.set("kapt.use.worker.api", "true")
    }
    if (!appProject.hasProperty("kapt.incremental.apt")) {
        appProject.rootProject.extensions.extraProperties.set("kapt.incremental.apt", "true")
    }
    if (!appProject.hasProperty("kapt.classloaders.cache.size")) {
        appProject.rootProject.extensions.extraProperties.set("kapt.classloaders.cache.size", "5")
    }
    if (!appProject.hasProperty("kapt.include.compile.classpath")) {
        appProject.rootProject.extensions.extraProperties.set("kapt.include.compile.classpath", "false")
    }
    if (!appProject.hasProperty("org.gradle.caching")) {
        appProject.rootProject.extensions.extraProperties.set("org.gradle.caching", "true")
    }
    if (!appProject.hasProperty("android.enableBuildCache")) {
        appProject.rootProject.extensions.extraProperties.set("android.enableBuildCache", "true")
    }

    appProject.gradle.startParameter.isParallelProjectExecutionEnabled = true
    val android = appProject.extensions.getByType(AppExtension::class.java)
    android.aaptOptions.cruncherEnabled = false
    android.aaptOptions.cruncherProcesses = 0

}

fun speedBuildByOption(appProject: Project, appExtension: AppExtension) {
    val configuredTransforms = (
        appProject.findProperty("rocketx.excludeTransforms")
            ?: appProject.findProperty("excludeTransForms")
        )?.toString()
        ?.split(Regex("\\s+"))
        ?.filter { it.isNotBlank() }
        .orEmpty()

    // dev_zy preserves every transform unless the build explicitly opts out.
    if (configuredTransforms.isEmpty()) return

    val transformsFiled = BaseExtension::class.members.firstOrNull { it.name == "_transforms" }

    if (transformsFiled != null) {
        transformsFiled.isAccessible = true
        val xValue = transformsFiled.call(appExtension) as? MutableList<Transform>
        xValue?.removeAll {
            configuredTransforms.contains(it.name)
        }

        if ((xValue?.size ?: 0) > 0) {
            println("RocketXPlugin : the following transform were detected : ")
            xValue?.forEach {
                println("transform: " + it.name)
            }
            println("RocketXPlugin : only rocketx.excludeTransforms entries are disabled")
        }
    }
}

/**
 * idea 插件不下载源码
 */
fun speedSync(appProject: Project){
    appProject.rootProject.allprojects { p ->
        val ideaPlugin = p.plugins.findPlugin(IdeaPlugin::class.java)
        if (ideaPlugin != null) {
            val ideaModule: IdeaModule? = ideaPlugin.model?.module
            ideaModule?.isDownloadSources = false
        }
    }
}

fun flatDirs(appProject: Project) {
    val map = mutableMapOf<String, File>()
    map["dirs"] = File(FileUtil.getLocalMavenCacheDir())
    appProject.rootProject.allprojects {
        it.repositories.flatDir(map)
    }
}
