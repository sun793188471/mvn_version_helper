package com.github.sun793188471.mvnversionhelper.services

import com.github.sun793188471.mvnversionhelper.settings.MavenVersionHelperSettings
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import git4idea.GitUtil

@Service(Service.Level.PROJECT)
class MavenVersionService(private val project: Project) {

    private val logger = Logger.getInstance(MavenVersionService::class.java)

    fun findPomFiles(): List<XmlFile> {
        val psiManager = PsiManager.getInstance(project)
        val pomFiles = mutableListOf<XmlFile>()
        val settings = MavenVersionHelperSettings.getInstance(project)
        val excludedPaths = settings.getExcludedPaths()
        val basePath = project.basePath ?: ""

        // 查找所有pom.xml文件
        val virtualFiles = FilenameIndex.getVirtualFilesByName(
            "pom.xml",
            GlobalSearchScope.projectScope(project)
        )

        virtualFiles.forEach { virtualFile ->
            val relativePath = virtualFile.path.removePrefix(basePath)

            // 检查是否应该排除此文件
            val shouldExclude = excludedPaths.any { excludePath ->
                relativePath.contains(excludePath)
            }

            if (!shouldExclude) {
                val psiFile = psiManager.findFile(virtualFile)
                if (psiFile is XmlFile) {
                    pomFiles.add(psiFile)
                }
            } else {
                logger.info("Excluded POM file: $relativePath")
            }
        }

        return pomFiles
    }

    fun getCurrentVersionString(pomFile: XmlFile): String? {
        val rootTag = pomFile.rootTag ?: return null
        if (rootTag.name != "project") return null

        // 首先查找直接的version标签
        val versionTag = rootTag.findFirstSubTag("version")
        if (versionTag != null) {
            return versionTag.value.text
        }

        // 如果没有找到，查找parent中的version
        val parentTag = rootTag.findFirstSubTag("parent")
        if (parentTag != null) {
            val parentVersionTag = parentTag.findFirstSubTag("version")
            if (parentVersionTag != null) {
                return parentVersionTag.value.text
            }
        }

        return null
    }

    fun getCurrentVersion(pomFile: XmlFile): XmlTag? {
        val rootTag = pomFile.rootTag ?: return null
        if (rootTag.name != "project") return null

        // 首先查找直接的version标签
        val versionTag = rootTag.findFirstSubTag("version")
        if (versionTag != null) {
            return versionTag
        }

        // 如果没有找到，查找parent中的version
        val parentTag = rootTag.findFirstSubTag("parent")
        if (parentTag != null) {
            val parentVersionTag = parentTag.findFirstSubTag("version")
            if (parentVersionTag != null) {
                return parentVersionTag
            }
        }
        return null
    }

    fun updateVersion(pomFile: XmlFile, newVersion: String): Boolean {
        return try {
            var updated = false

            WriteCommandAction.runWriteCommandAction(project) {
                val rootTag = pomFile.rootTag ?: return@runWriteCommandAction
                if (rootTag.name != "project") return@runWriteCommandAction

                // 更新项目的version标签
                val currentVersion = this.getCurrentVersion(pomFile)
                if (currentVersion != null) {
                    currentVersion.value.text = newVersion
                    updated = true
                } else {
                    // 如果没有version标签，直接记录警告
                    logger.warn("Failed to update version in pom.xml: ${pomFile.virtualFile.path}, no version tag found")
                }
            }

            updated
        } catch (e: Exception) {
            logger.warn("Failed to update version in pom.xml: ${pomFile.virtualFile.path}", e)
            false
        }
    }

    private fun incrementPatch(version: String): String {
        val parts = version.split(".")
        if (parts.size >= 3) {
            val major = parts[0]
            val minor = parts[1]
            val patch = parts[2].toIntOrNull() ?: 0
            return "$major.$minor.${patch + 1}"
        }
        return version
    }

    private fun incrementMinor(version: String): String {
        val parts = version.split(".")
        if (parts.size >= 2) {
            val major = parts[0]
            val minor = parts[1].toIntOrNull() ?: 0
            return "$major.${minor + 1}.0"
        }
        return version
    }

    private fun incrementMajor(version: String): String {
        val parts = version.split(".")
        if (parts.isNotEmpty()) {
            val major = parts[0].toIntOrNull() ?: 0
            return "${major + 1}.0.0"
        }
        return version
    }

    /**
     * 获取当前git分支名
     */
    fun getCurrentBranch(): String? {
        return try {
            val repositories = GitUtil.getRepositoryManager(project).repositories
            val repo = repositories.firstOrNull() ?: return null
            repo.currentBranchName
        } catch (e: Exception) {
            logger.warn("Failed to get current branch", e)
            null
        }
    }

    /**
     * 根据分支类型自动生成新版本号
     */
    fun autoGenerateNewVersionByBranch(): String? {
        val branch = getCurrentBranch() ?: return null
        val env = when {
            branch.startsWith("release") -> "release"
            branch.contains("qa", ignoreCase = true) -> "qa"
            branch.contains("uat", ignoreCase = true) -> "uat"
            branch.contains("task", ignoreCase = true) -> "task"
            else -> "other"
        }

        // 获取当前版本并生成下一个版本
        val pomFiles = findPomFiles()
        if (pomFiles.isEmpty()) return null

        val currentVersion = getCurrentVersionString(pomFiles.first()) ?: return null
        val nextVersion = incrementPatch(currentVersion.replace("-SNAPSHOT", ""))

        return if (env == "release") nextVersion else "$nextVersion-SNAPSHOT"
    }
}