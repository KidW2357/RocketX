package plugin.utils

import getMavenArtifactId
import getMavenGroupId
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.internal.artifacts.dependencies.DefaultProjectDependency
import org.gradle.api.internal.artifacts.dependencies.DefaultSelfResolvingDependency
import org.gradle.api.internal.file.collections.DefaultConfigurableFileCollection
import org.gradle.api.internal.file.collections.DefaultConfigurableFileTree
import plugin.ChildProjectDependencies
import plugin.bean.RocketXBean
import java.io.File

/**
 * description:
 * author chaojiong.zhang
 * data: 2021/10/25
 * copyright TCL+
 */
class DependenciesHelper(
    private val rocketXBean: RocketXBean?,
    var mProjectDependenciesList: MutableList<ChildProjectDependencies>) {

    private val enableLocalMaven by lazy {
        rocketXBean?.localMaven ?: false
    }

    /**
     * A timestamp snapshot can survive while an individual cached artifact is removed.
     * Fall back to source for that project and all of its consumers instead of silently
     * removing the project dependency without adding an AAR/JAR replacement.
     */
    fun markProjectsWithMissingArtifacts(changedProjects: MutableMap<String, Project>?) {
        if (changedProjects == null) return

        mProjectDependenciesList.forEach { wrapper ->
            val project = wrapper.project
            if (changedProjects.containsKey(project.path)) return@forEach

            val extension = when {
                hasAndroidPlugin(project) -> ".aar"
                hasJavaPlugin(project) -> ".jar"
                else -> null
            } ?: return@forEach

            val cacheFile = File(
                FileUtil.getLocalMavenCacheDir(),
                getFlatAarName(project) + extension
            )
            if (!cacheFile.isFile) {
                changedProjects[project.path] = project
                LogUtil.d("cache missing, compile from source: ${project.path}")
            }
        }
    }

    /**
     * If a project changes, every local project that compiles against it must also be
     * rebuilt. Reusing a parent AAR after a child API/resource change can otherwise
     * produce a successful build with stale bytecode or resources.
     */
    fun propagateChangedProjects(changedProjects: MutableMap<String, Project>?) {
        if (changedProjects == null) return

        var foundNewConsumer: Boolean
        do {
            foundNewConsumer = false
            mProjectDependenciesList.forEach { wrapper ->
                val consumer = wrapper.project
                if (changedProjects.containsKey(consumer.path)) return@forEach

                val dependsOnChangedProject = consumer.configurations.any { configuration ->
                    configuration.dependencies.any { dependency ->
                        dependency is ProjectDependency &&
                            changedProjects.containsKey(dependency.dependencyProject.path)
                    }
                }
                if (dependsOnChangedProject) {
                    changedProjects[consumer.path] = consumer
                    foundNewConsumer = true
                    LogUtil.d("dependency changed, compile from source: ${consumer.path}")
                }
            }
        } while (foundNewConsumer)
    }

    //获取第一层 parent 依赖当前 project
    private fun getFirstLevelParentDependencies(project: Project): MutableMap<Project, MutableList<Configuration>> {
        val parentProjectList = mutableMapOf<Project, MutableList<Configuration>>()
        mProjectDependenciesList.forEach {
            val parentProject = it.project
            //子project 所有的 config
            it.allConfigList.forEach { config ->
                //每一个config 所有依赖
                run loop@{
                    config.dependencies.forEach { dependency ->
                        //项目依赖
                        if (dependency is ProjectDependency && dependency.dependencyProject.path == project.path) {
                            parentProjectList.get(parentProject)?.apply {
                                this.add(config)
                            } ?: let {
                                val configList = mutableListOf<Configuration>()
                                configList.add(config)
                                parentProjectList.put(parentProject, configList)
                            }
                            //每一个 config 对同个 project 重复依赖是无意义，可直接 return
                            return@loop
                        }
                    }
                }
            }
        }
        return parentProjectList
    }

    /**
     * 解决各个 project 变动之后需要打成 aar 包，算法V1
     */
    fun modifyDependencies(projectWapper: ChildProjectDependencies) {
        val isAndroidLib = hasAndroidPlugin(projectWapper.project)
        val isJavaLib = hasJavaPlugin(projectWapper.project)
        // Only source libraries have RocketX-generated, project-named cache artifacts.
        // Preserve binary-only projects (artifacts.add("default", file("vendor.aar")))
        // and their original configuration, artifact names and transitive dependencies.
        if (!isAndroidLib && !isJavaLib) return

        //找到所有的父依赖
        val map = getFirstLevelParentDependencies(projectWapper.project)
        //可能有多个父依赖，所以需要遍历
        map.forEach { parentProject ->

            //父依赖的 configuration 添加 当前的 project 对应的aar
            parentProject.value.forEach { parentConfig ->
                // 剔除原有的依赖
                parentConfig.dependencies.removeAll { dependency ->
                    dependency is ProjectDependency &&
                        dependency.dependencyProject.path == projectWapper.project.path
                }

                // 需要根据RocketXBean配置，区分使用本地aar还是maven的依赖方式
                if (enableLocalMaven) {
                    addMavenDependencyToProject(projectWapper.project,
                        parentConfig.name,
                        parentProject.key,
                        isAndroidLib)
                } else {
                    //android source module
                    if (isAndroidLib) {
                        addAarDependencyToProject(getFlatAarName(projectWapper.project),
                            parentConfig.name,
                            parentProject.key)
                    } else {
                        //java module
                        addJarDependencyToProject(getFlatAarName(projectWapper.project),
                            parentConfig.name,
                            parentProject.key)
                    }
                }

                // 把子 project 自身的依赖全部 给到 父 project
                projectWapper.allConfigList.forEach { childConfig ->
                    childConfig.dependencies.forEach { childDepency ->
                        if (childDepency is DefaultProjectDependency) {
                            if (childDepency.targetConfiguration == null) {
                                childDepency.targetConfiguration = "default"
                            }
                            // Android Studio 4.0.0 索引
                            val dependencyClone = childDepency.copy()
                            dependencyClone.targetConfiguration = null
                            // parent 铁定有 childConfig.name 的 config
                            parentProject.key.dependencies.add(childConfig.name, dependencyClone)
                        } else {
                            if (childDepency is DefaultSelfResolvingDependency && (childDepency.files is DefaultConfigurableFileCollection || childDepency.files is DefaultConfigurableFileTree)) {
                                // 这里的依赖是以下两种： 无需添加在 parent ，因为 jar 包直接进入 自身的 aar 中的libs 文件夹
                                //    implementation rootProject.files("libs/tingyun-ea-agent-android-2.15.4.jar")
                                //    implementation fileTree(dir: "libs", include: ["*.jar"])


                            } else {
                                parentProject.key.dependencies.add(childConfig.name, childDepency)
                            }
                        }
                    }
                }
            }
        }
    }


    private fun addAarDependencyToProject(aarName: String, configName: String, project: Project) {
        //添加 aar 依赖 以下代码等同于 api/implementation/xxx (name: 'libaccount-2.0.0', ext: 'aar'),源码使用 linkedMap
        val artifact = File(FileUtil.getLocalMavenCacheDir(), aarName + ".aar")
        if (!artifact.isFile) {
            throw GradleException("RocketX cache is missing: ${artifact.absolutePath}")
        }
        val map = linkedMapOf<String, String>()
        map.put("name", aarName)
        map.put("ext", "aar")
        project.dependencies.add(configName, map)
    }

    private fun addJarDependencyToProject(aarName: String, configName: String, project: Project) {
        //添加 jar 依赖
        val artifact = File(FileUtil.getLocalMavenCacheDir(), aarName + ".jar")
        if (!artifact.isFile) {
            throw GradleException("RocketX cache is missing: ${artifact.absolutePath}")
        }
        val map = linkedMapOf<String, String>()
        map.put("name", aarName)
        map.put("ext", "jar")
        project.dependencies.add(configName, map)
    }

    private fun addMavenDependencyToProject(
        child: Project, configName: String, project: Project, isAndroid: Boolean) {
        // 改变依赖 这里后面需要修改成maven依赖
        if (isAndroid) {
            project.dependencies.add(configName,
                "${child.getMavenGroupId()}:${child.getMavenArtifactId()}:1.0@aar")
        } else {
            project.dependencies.add(configName,
                "${child.getMavenGroupId()}:${child.getMavenArtifactId()}:1.0@jar")
        }
    }

    /**
     * 解决各个 project 变动之后需要打成 aar 包,算法 V2
     */
    fun modifyDependenciesV2(projectWapper: ChildProjectDependencies) {
        //todo

    }


}
