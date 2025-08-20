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
     * 从远端获取当前分支的信息
     */
    fun getRealBranchName(): String? {
        return try {
            val repositories = GitUtil.getRepositoryManager(project).repositories
            val repo = repositories.firstOrNull() ?: return null

            val currentBranch = repo.currentBranchName ?: return null

            var branchName: String?;
            // 获取当前分支的上游分支信息
            val localBranch = repo.branches.findLocalBranch(currentBranch)
            if (localBranch != null) {
                val trackingInfo = repo.branchTrackInfos.find { it.localBranch == localBranch }
                val remoteBranch = trackingInfo?.remoteBranch

                if (remoteBranch != null) {
                    // 返回实际分支名称，如 origin/feature/xxx
                    branchName = remoteBranch.name
                    return branchName.substringAfterLast("/");
                }
            }

            // 如果没有找到跟踪信息，尝试查找同名的远端分支
            val remoteBranches = repo.branches.remoteBranches
            val matchingRemote = remoteBranches.find {
                it.name.endsWith("/$currentBranch")
            }

            matchingRemote?.name ?: currentBranch

        } catch (e: Exception) {
            logger.warn("Failed to get remote branch info", e)
            null
        }
    }

    /**
     * 更新指定依赖的版本
     */
    fun updateDependencyVersion(pomFile: XmlFile, groupId: String, artifactId: String, newVersion: String): Boolean {
        return try {
            var updated = false

            WriteCommandAction.runWriteCommandAction(project) {
                val rootTag = pomFile.rootTag ?: return@runWriteCommandAction
                val dependenciesTag = rootTag.findFirstSubTag("dependencies") ?: return@runWriteCommandAction
                val dependencyTags = dependenciesTag.findSubTags("dependency")

                dependencyTags.forEach { depTag ->
                    val depGroupId = depTag.findFirstSubTag("groupId")?.value?.text
                    val depArtifactId = depTag.findFirstSubTag("artifactId")?.value?.text

                    if (depGroupId == groupId && depArtifactId == artifactId) {
                        val versionTag = depTag.findFirstSubTag("version")
                        if (versionTag != null) {
                            versionTag.value.text = newVersion
                            updated = true
                        }
                    }
                }
            }

            updated
        } catch (e: Exception) {
            logger.warn("Failed to update dependency version: $groupId:$artifactId", e)
            false
        }
    }

    /**
     * 获取当前项目的远端版本信息
     */
    fun getCurrentProjectRemoteVersions(branchType: BranchType? = null, pomFiles: List<XmlFile>): Pair<String?, String?> {
        if (pomFiles.isEmpty()) return Pair(null, null)

        val mainPomFile = pomFiles.first()
        val rootTag = mainPomFile.rootTag ?: return Pair(null, null)

        val groupId = rootTag.findFirstSubTag("groupId")?.value?.text
            ?: rootTag.findFirstSubTag("parent")?.findFirstSubTag("groupId")?.value?.text
        val artifactId = rootTag.findFirstSubTag("artifactId")?.value?.text

        return if (groupId != null && artifactId != null) {
            try {
                val repositoryService = MavenRepositoryService.getInstance(project)
                repositoryService.getRemoteVersions(groupId, artifactId, branchType)
            } catch (e: Exception) {
                logger.warn("Failed to get remote versions for current project: $groupId:$artifactId", e)
                Pair(null, null)
            }
        } else {
            Pair(null, null)
        }
    }

    /**
     * 获取当前项目的 GroupId 和 ArtifactId
     * 优先选择目录结构最外层并且包含 <packaging>pom</packaging> 的 pom 文件
     */
    fun getParentProjectInfo(pomFiles: List<XmlFile>): Pair<String?, String?> {
        if (pomFiles.isEmpty()) return Pair(null, null)

        // 按路径深度排序所有 pom 文件（路径越短，越靠外层）
        val sortedPomFiles = pomFiles.sortedBy { it.virtualFile.path.count { c -> c == '/' } }

        // 首先尝试找到最外层且包含 <packaging>pom</packaging> 的 pom 文件
        val rootPomFile = sortedPomFiles.find { pomFile ->
            val rootTag = pomFile.rootTag ?: return@find false
            val packagingTag = rootTag.findFirstSubTag("packaging")
            packagingTag?.value?.text == "pom"
        } ?: sortedPomFiles.first() // 如果没找到符合条件的，就用最外层的 pom 文件

        val rootTag = rootPomFile.rootTag ?: return Pair(null, null)

        val groupId = rootTag.findFirstSubTag("groupId")?.value?.text
            ?: rootTag.findFirstSubTag("parent")?.findFirstSubTag("groupId")?.value?.text
        val artifactId = rootTag.findFirstSubTag("artifactId")?.value?.text

        return Pair(groupId, artifactId)
    }

    /**
     * 识别分支类型 - 基于远端分支名称
     */
    fun getBranchType(branchName: String?): BranchType {
        if (branchName == null) return BranchType.OTHER

        return when {
            // 直接匹配
            branchName.equals("master", ignoreCase = true) -> BranchType.MASTER
            branchName.equals("qa", ignoreCase = true) -> BranchType.QA
            branchName.equals("uat", ignoreCase = true) -> BranchType.UAT
            branchName.equals("hotfix", ignoreCase = true) -> BranchType.HOTFIX
            branchName.equals("release", ignoreCase = true) -> BranchType.RELEASE

            // walle/fix-walle/ 开头的分支
            branchName.startsWith("walle/fix-walle/") -> {
                val suffix = branchName.substringAfterLast("-")
                when {
                    suffix.contains("-qa-", ignoreCase = true) -> BranchType.QA
                    suffix.contains("-uat-", ignoreCase = true) -> BranchType.UAT
                    suffix.contains("-hotfix-", ignoreCase = true) -> BranchType.HOTFIX
                    suffix.contains("-release-", ignoreCase = true) -> BranchType.RELEASE
                    else -> BranchType.OTHER
                }
            }

            // walle/Conflict_ 开头的分支
            branchName.startsWith("walle/Conflict_") -> {
                val conflictType = branchName.substringAfter("walle/Conflict_").substringBefore("_")
                when (conflictType.lowercase()) {
                    "qa" -> BranchType.QA
                    "uat" -> BranchType.UAT
                    "hotfix" -> BranchType.HOTFIX
                    "release" -> BranchType.RELEASE
                    "master" -> BranchType.MASTER
                    else -> BranchType.OTHER
                }
            }

            // 开发分支 Task_12345_XXX
            branchName.contains("Task_") -> {
                BranchType.TASK
            }

            else -> BranchType.OTHER
        }
    }

    /**
     * 从开发分支中提取任务号 - 基于清理后的分支名称
     */
    fun extractTaskNumber(branchName: String): String? {
        val taskRegex = Regex("Task_(\\d+)_")
        val match = taskRegex.find(branchName)
        return match?.groupValues?.get(1)
    }

    /**
     * 根据分支类型智能推荐版本号
     */
    fun getRecommendedVersion(
        branchType: BranchType,
        branchName: String?,
        currentProjectVersion: Pair<String?, String?>
    ): String? {
        try {
            val release = currentProjectVersion.first;
            // 获取远端最新的 RELEASE 版本
            if (release == null) {
                logger.warn("无法获取Release 版本，无法推荐版本号")
                return null
            }
            // 解析版本号并增加小版本
            val nextVersion = incrementMinorVersion(release)

            return when (branchType) {
                BranchType.MASTER, BranchType.HOTFIX, BranchType.RELEASE -> {
                    "${nextVersion}.RELEASE"
                }

                BranchType.QA -> {
                    "${nextVersion}-qa-SNAPSHOT"
                }

                BranchType.UAT -> {
                    "${nextVersion}-uat-SNAPSHOT"
                }

                BranchType.TASK -> {
                    val taskNumber = branchName?.let { extractTaskNumber(it) }
                    if (taskNumber != null) {
                        "${nextVersion}-${taskNumber}-SNAPSHOT"
                    } else {
                        "${nextVersion}-SNAPSHOT"
                    }
                }

                BranchType.OTHER -> {
                    "${nextVersion}-SNAPSHOT"
                }
            }
        } catch (e: Exception) {
            logger.warn("推荐版本号生成失败", e)
            return null
        }
    }

    /**
     * 增加小版本号 - 升级最后一位，最后一位大于100时升级前面一位
     */
    private fun incrementMinorVersion(version: String): String {
        // 分离版本号和后缀
        val suffixRegex = Regex("(\\.RELEASE|-SNAPSHOT).*$")
        val baseVersion = version.replace(suffixRegex, "")

        val parts = baseVersion.split(".")
        if (parts.isEmpty()) return "1.0"

        // 只处理数字部分，跳过包含字母的部分
        val versionNumbers = mutableListOf<String>()
        var lastNumericIndex = -1

        for (i in parts.indices) {
            val numericValue = parts[i].toIntOrNull()
            if (numericValue != null) {
                versionNumbers.add(parts[i])
                lastNumericIndex = i
            } else {
                versionNumbers.add(parts[i])
            }
        }

        // 如果没有找到数字部分，返回原版本
        if (lastNumericIndex == -1) return version

        // 从最后一个数字部分开始升级
        var index = lastNumericIndex

        while (index >= 0) {
            val currentValue = versionNumbers[index].toIntOrNull()
            if (currentValue == null) {
                index--
                continue
            }

            val newValue = currentValue + 1
            versionNumbers[index] = newValue.toString()

            // 如果当前位数字大于100，需要升级前一位
            if (newValue > 100) {
                versionNumbers[index] = "1"  // 当前位重置为1
                if (index == 0) {
                    // 如果已经是第一位，在前面添加新的位
                    versionNumbers.add(0, "1")
                    versionNumbers[1] = "1"
                    break
                }
                // 继续处理前一位数字
                index--
                while (index >= 0 && versionNumbers[index].toIntOrNull() == null) {
                    index--
                }
            } else {
                // 当前位处理完成，跳出循环
                break
            }
        }

        return versionNumbers.joinToString(".")
    }

    enum class BranchType(val displayName: String) {
        MASTER("Master分支"),
        QA("QA分支"),
        UAT("UAT分支"),
        HOTFIX("Hotfix分支"),
        RELEASE("Release分支"),
        TASK("开发分支"),
        OTHER("其他分支")
    }
}