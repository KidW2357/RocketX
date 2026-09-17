package plugin.utils

import com.google.gson.Gson
import org.gradle.api.Project
import plugin.bean.ModuleChangeTime
import plugin.bean.ModuleChangeTimeList
import plugin.utils.FileUtil.eachFileRecurse
import plugin.utils.FileUtil.writeFileToModuleJson
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * description:
 * author chaojiong.zhang
 * data: 2021/11/5
 * copyright TCL+
 *
 *  module 变动计算
 */
object ChangeModuleUtils {
    private const val ROOT_BUILD_TAG = "__rocketx_root_build__"
    private val ignoredDirectories = setOf("build", ".gradle", ".git", ".idea", ".cxx")
    private val rootBuildDirectories = setOf("gradle", "buildSrc")

    //Gradle 静态变量会被保留
    private val newModuleList: MutableList<ModuleChangeTime> = mutableListOf()

    /**
     * 获取发生变动的module信息
     */
    fun getChangeModuleMap(project: Project): MutableMap<String, Project>? {
        val changeMap: MutableMap<String, Project> = mutableMapOf()
        val startTime = System.currentTimeMillis()

        getNewModuleList(project)

        val localModuleList = FileUtil.getLocalModuleChange()

        localModuleList?.let { localFile ->
            try {
                val oldModuleList = Gson().fromJson(localFile.readText(), ModuleChangeTimeList::class.java)
                // 返回null, 代表之前没有编译过，要重新编译
                if (oldModuleList.list.isNullOrEmpty()) {
                    allProjectsChange(project,changeMap)
                } else {
                    val newRootBuild = newModuleList.first { it.moduleName == ROOT_BUILD_TAG }
                    val oldRootBuild = oldModuleList.list.firstOrNull { it.moduleName == ROOT_BUILD_TAG }
                    if (oldRootBuild == null || oldRootBuild.changeTag != newRootBuild.changeTag) {
                        LogUtil.d("root build configuration changed")
                        allProjectsChange(project, changeMap)
                    }

                    newModuleList.filter { it.moduleName != ROOT_BUILD_TAG }.forEach { newModule ->
                        oldModuleList.list.firstOrNull { newModule.moduleName == it.moduleName }.also { moduleChange ->
                            // 为null, 代表这个module是新创建的
                            if (moduleChange == null) {
                                project.rootProject.allprojects.firstOrNull { pt ->
                                    pt?.path == newModule.moduleName
                                }?.let {
                                    changeMap[newModule.moduleName] = it
                                    LogUtil.d(" 你添加了=${newModule.moduleName}        ")
                                }
                            }
                            // 已有的module 文件发生改变
                            else if (moduleChange.changeTag != newModule.changeTag) {
                                changeMap[newModule.moduleName] = project.rootProject.allprojects.first { it?.path == newModule.moduleName }
                                LogUtil.d(" 你修改了=${newModule.moduleName}      ")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                LogUtil.d("invalid module snapshot, rebuild from source: ${e.message}")
                allProjectsChange(project, changeMap)
            }
        } ?: run {
            allProjectsChange(project,changeMap)
        }

        //最后补一个 app 的 module，app 是认为做了改变，不打成 aar
        changeMap[project.path] = project
        LogUtil.d("count time====>>>> ${System.currentTimeMillis() - startTime}ms   "+changeMap.toString())
        return changeMap
    }

    /**
     * 如果没有这个文件的话，认为整个模块都做了改动
     */
    private fun allProjectsChange(project: Project,changeMap: MutableMap<String, Project>) {
        project.rootProject.allprojects.filter { it != project.rootProject && it.childProjects.isEmpty() }.forEach {
            changeMap[it.path] = it
        }
    }

    /**
     *  获取当前module和文件时间戳
     */
    private fun getNewModuleList(project: Project) {
        newModuleList.clear()
        var count = 0
        newModuleList.add(ModuleChangeTime(ROOT_BUILD_TAG, getRootBuildFingerprint(project)))

        project.rootProject.allprojects.onEach {
            if (it == project.rootProject || it.childProjects.isNotEmpty()) {
                return@onEach
            }
            val files = collectTrackedFiles(it.projectDir)
            count += files.size
            newModuleList.add(ModuleChangeTime(it.path, fingerprint(it.projectDir, files)))
        }
        LogUtil.d("total file num ====>>>> "+ count)
    }

    private fun getRootBuildFingerprint(project: Project): Long {
        val rootDir = project.rootProject.rootDir
        val files = mutableListOf<File>()
        rootDir.listFiles()?.forEach { file ->
            if (file.isFile && isRootBuildFile(file)) {
                files.add(file)
            } else if (file.isDirectory && rootBuildDirectories.contains(file.name)) {
                files.addAll(collectTrackedFiles(file))
            }
        }
        return fingerprint(rootDir, files)
    }

    private fun isRootBuildFile(file: File): Boolean {
        return file.name.endsWith(".gradle") ||
            file.name.endsWith(".gradle.kts") ||
            file.name.endsWith(".properties") ||
            file.name == "gradlew" ||
            file.name == "gradlew.bat"
    }

    private fun collectTrackedFiles(root: File): List<File> {
        val files = mutableListOf<File>()
        root.eachFileRecurse { file ->
            if (file.isDirectory) {
                !ignoredDirectories.contains(file.name)
            } else {
                files.add(file)
                true
            }
        }
        return files
    }

    private fun fingerprint(root: File, files: List<File>): Long {
        val digest = MessageDigest.getInstance("SHA-256")
        files.sortedBy { it.relativeTo(root).invariantSeparatorsPath }.forEach { file ->
            val relativePath = file.relativeTo(root).invariantSeparatorsPath
            digest.update(relativePath.toByteArray(StandardCharsets.UTF_8))
            digest.update(0.toByte())
            digest.update(file.length().toString().toByteArray(StandardCharsets.UTF_8))
            digest.update(0.toByte())
            digest.update(file.lastModified().toString().toByteArray(StandardCharsets.UTF_8))
            digest.update(0.toByte())
        }
        return ByteBuffer.wrap(digest.digest()).getLong()
    }


    fun flushJsonFile() {
        val dir = File(FileUtil.getLocalMavenCacheDir())
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val jsonFile = File(dir, Contants.MODULE_CHANGE_TIME)
        if (!jsonFile.exists()) {
            jsonFile.createNewFile()
        }
        jsonFile.writeFileToModuleJson(newModuleList)
    }

}

