package com.github.sun793188471.mvnversionhelper.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.net.URL

@Service(Service.Level.PROJECT)
class MavenRepositoryService(private val project: Project) {

    private val logger = Logger.getInstance(MavenRepositoryService::class.java)

    data class VersionInfo(
        val groupId: String,
        val artifactId: String,
        val latestRelease: String? = null,
        val latestSnapshot: String? = null,
        val currentVersion: String? = null
    )

    private fun getMavenProjectsManager(): MavenProjectsManager {
        return MavenProjectsManager.getInstance(project)
    }

    /**
     * 从项目的Maven配置中获取仓库URL列表
     */
    private fun getRepositoryUrls(): List<String> {
        val repositories = mutableSetOf<String>()

        try {
            val mavenProjectsManager = getMavenProjectsManager()
            val mavenProjects = mavenProjectsManager.projects

            // 从每个Maven项目获取仓库配置
            mavenProjects.forEach { mavenProject ->
                // 获取项目配置的仓库
                mavenProject.remoteRepositories.forEach { repo ->
                    repositories.add(repo.url)
                }
            }

            // 如果没有配置仓库，添加默认的中央仓库
            if (repositories.isEmpty()) {
                repositories.add("https://repo.maven.apache.org/maven2/")
            }

        } catch (e: Exception) {
            logger.warn("获取Maven仓库配置失败", e)
            repositories.add("https://repo.maven.apache.org/maven2/")
        }

        logger.info("找到Maven仓库: $repositories")
        return repositories.toList()
    }

    /**
     * 查询远程仓库中的版本信息
     */
    fun getRemoteVersions(
        groupId: String,
        artifactId: String,
        branchType: MavenVersionService.BranchType? = null
    ): Pair<String?, String?> {
        val repositories = getRepositoryUrls()

        for (repoUrl in repositories) {
            try {
                val versions = getVersionsFromRepository(repoUrl, groupId, artifactId, branchType)
                if (versions.first != null || versions.second != null) {
                    logger.info("从仓库 $repoUrl 获取到版本信息: Release=${versions.first}, Latest=${versions.second}")
                    return versions
                }
            } catch (e: Exception) {
                logger.warn("从仓库 $repoUrl 获取版本失败: $groupId:$artifactId", e)
                continue
            }
        }
        return Pair(null, null)
    }

    /**
     * 从指定仓库获取版本列表
     */
    private fun getVersionsFromRepository(
        repoUrl: String,
        groupId: String,
        artifactId: String,
        branchType: MavenVersionService.BranchType? = null
    ): Pair<String?, String?> {
        try {
            val baseUrl = repoUrl.removeSuffix("/")
            val groupPath = groupId.replace(".", "/")
            val metadataUrl = "$baseUrl/$groupPath/$artifactId/maven-metadata.xml"

            logger.info("正在访问元数据URL: $metadataUrl")

            val url = URL(metadataUrl)
            val connection = url.openConnection()
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.setRequestProperty("User-Agent", "Maven-Repository-Service/1.0")

            val xmlContent = connection.getInputStream().bufferedReader().use { it.readText() }
            logger.debug("获取到XML内容: $xmlContent")

            // 解析XML获取版本信息
            return parseVersionsFromMetadata(xmlContent, branchType)

        } catch (e: Exception) {
            logger.debug("无法从 $repoUrl 获取 $groupId:$artifactId 的元数据: ${e.message}")
            return Pair(null, null)
        }
    }

    private fun parseVersionsFromMetadata(
        xmlContent: String,
        branchType: MavenVersionService.BranchType?
    ): Pair<String?, String?> {
        try {
            var releaseVersion: String? = null
            var latestSnapshot: String? = null

            // 获取 release 版本（保持原有逻辑）
            val releaseRegex = Regex("<release>(.*?)</release>")
            val releaseMatch = releaseRegex.find(xmlContent)
            releaseVersion = releaseMatch?.groupValues?.get(1)?.trim()

            // 获取所有版本号
            val versionRegex = Regex("<version>(.*?)</version>")
            val allVersions = versionRegex.findAll(xmlContent)
                .map { it.groupValues[1] }
                .toList()
            // 过滤 SNAPSHOT 版本
            val snapshotVersions = allVersions.filter { version ->
                version.endsWith("-SNAPSHOT", ignoreCase = true)
            }

            // 根据分支类型进行进一步筛选和排序
            when (branchType) {
                MavenVersionService.BranchType.QA -> {
                    val qaVersions = snapshotVersions.filter { version ->
                        version.contains("qa", ignoreCase = true)
                    }
                    latestSnapshot = qaVersions.maxWithOrNull(VersionComparator())
                }

                MavenVersionService.BranchType.UAT -> {
                    val uatVersions = snapshotVersions.filter { version ->
                        version.contains("uat", ignoreCase = true)
                    }
                    latestSnapshot = uatVersions.maxWithOrNull(VersionComparator())
                }

                MavenVersionService.BranchType.TASK -> {
                    // 匹配包含任务号的 SNAPSHOT 版本（数字格式）
                    val taskVersions = snapshotVersions.filter { version ->
                        val hasTaskNumber = Regex("-\\d+-SNAPSHOT$", RegexOption.IGNORE_CASE).containsMatchIn(version)
                        val isNotQaUat = !version.contains("qa", ignoreCase = true) &&
                                !version.contains("uat", ignoreCase = true)
                        hasTaskNumber && isNotQaUat
                    }
                    // 直接倒序取最新版本，因为 version 列表本身就是按发布时间排序的
                    latestSnapshot = taskVersions.lastOrNull()
                }

                else -> {
                    // 其他情况获取所有 SNAPSHOT 中的最新版本
                    latestSnapshot = snapshotVersions.maxWithOrNull(VersionComparator())
                }
            }

            return Pair(releaseVersion, latestSnapshot)
        } catch (e: Exception) {
            logger.warn("解析版本元数据失败", e)
            return Pair(null, null)
        }
    }

    /**
     * Maven 版本号比较器
     */
    private class VersionComparator : Comparator<String> {
        override fun compare(v1: String, v2: String): Int {
            return compareVersions(v1, v2)
        }

        private fun compareVersions(version1: String, version2: String): Int {
            val v1Parts = parseVersion(version1)
            val v2Parts = parseVersion(version2)

            // 先比较数字部分
            val numbersComparison = compareNumberParts(v1Parts.numbers, v2Parts.numbers)
            if (numbersComparison != 0) {
                return numbersComparison
            }

            // 数字部分相同，比较限定符
            return compareQualifiers(v1Parts.qualifier, v2Parts.qualifier)
        }

        private fun parseVersion(version: String): VersionParts {
            // 移除 -SNAPSHOT 后缀进行解析
            val cleanVersion = version.replace(Regex("-SNAPSHOT$", RegexOption.IGNORE_CASE), "")

            // 分离数字部分和限定符
            val parts = cleanVersion.split(Regex("[-.]"))
            val numbers = mutableListOf<Int>()
            val qualifierParts = mutableListOf<String>()

            var inQualifier = false

            for (part in parts) {
                val numValue = part.toIntOrNull()
                if (numValue != null && !inQualifier) {
                    numbers.add(numValue)
                } else {
                    inQualifier = true
                    qualifierParts.add(part)
                }
            }

            val qualifier = qualifierParts.joinToString("-")
            return VersionParts(numbers, qualifier)
        }

        private fun compareNumberParts(numbers1: List<Int>, numbers2: List<Int>): Int {
            val maxLength = maxOf(numbers1.size, numbers2.size)

            for (i in 0 until maxLength) {
                val n1 = numbers1.getOrElse(i) { 0 }
                val n2 = numbers2.getOrElse(i) { 0 }
                val comparison = n1.compareTo(n2)
                if (comparison != 0) {
                    return comparison
                }
            }

            return 0
        }

        private fun compareQualifiers(q1: String, q2: String): Int {
            if (q1.isEmpty() && q2.isEmpty()) return 0
            if (q1.isEmpty()) return 1  // 无限定符的版本更高
            if (q2.isEmpty()) return -1

            // 如果都是 SNAPSHOT 版本，比较具体的限定符
            return compareSnapshotQualifiers(q1, q2)
        }

        private fun compareSnapshotQualifiers(q1: String, q2: String): Int {
            // 提取任务号进行比较
            val task1 = extractTaskNumber(q1)
            val task2 = extractTaskNumber(q2)

            return when {
                task1 != null && task2 != null -> task1.compareTo(task2)
                task1 != null && task2 == null -> 1  // 有任务号的版本更高
                task1 == null && task2 != null -> -1
                else -> q1.compareTo(q2)  // 字符串比较
            }
        }

        private fun extractTaskNumber(qualifier: String): Int? {
            val taskRegex = Regex("(\\d+)")
            val match = taskRegex.find(qualifier)
            return match?.value?.toIntOrNull()
        }

        private data class VersionParts(
            val numbers: List<Int>,
            val qualifier: String
        )
    }

    /**
     * 从版本列表中选择最大的版本号
     */
    private fun getMaxVersion(versions: List<String>): String? {
        if (versions.isEmpty()) return null

        return versions.maxWithOrNull { v1, v2 ->
            compareVersions(v1, v2)
        }
    }

    /**
     * 版本号比较
     * 支持格式: 1.9.3.200-qa-SNAPSHOT, 1.7.3.40-uat-SNAPSHOT 等
     */
    private fun compareVersions(v1: String, v2: String): Int {
        // 提取版本号部分（去掉后缀）
        val version1 = extractVersionNumbers(v1)
        val version2 = extractVersionNumbers(v2)

        // 比较版本号数组
        val maxLength = maxOf(version1.size, version2.size)

        for (i in 0 until maxLength) {
            val num1 = version1.getOrElse(i) { 0 }
            val num2 = version2.getOrElse(i) { 0 }

            when {
                num1 > num2 -> return 1
                num1 < num2 -> return -1
            }
        }

        return 0
    }

    /**
     * 从版本字符串中提取数字部分
     * 例如: "1.9.3.200-qa-SNAPSHOT" -> [1, 9, 3, 200]
     */
    private fun extractVersionNumbers(version: String): List<Int> {
        // 移除后缀（如 -qa-SNAPSHOT, -uat-SNAPSHOT, -SNAPSHOT, -RELEASE 等）
        val baseVersion = version.replace(Regex("-(qa|uat|SNAPSHOT|RELEASE).*$", RegexOption.IGNORE_CASE), "")

        return baseVersion.split(".")
            .mapNotNull { it.toIntOrNull() }
    }

    companion object {
        fun getInstance(project: Project): MavenRepositoryService {
            return project.getService(MavenRepositoryService::class.java)
        }
    }
}