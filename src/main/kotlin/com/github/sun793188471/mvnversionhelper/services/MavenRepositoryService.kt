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
    fun getRemoteVersions(groupId: String, artifactId: String, branchType: MavenVersionService.BranchType? = null): Pair<String?, String?> {
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
            var latestVersion: String? = null
            var releaseVersion: String? = null

            // 解析所有版本
            val versionsRegex = Regex("<version>([^<]+)</version>")
            val allVersions = versionsRegex.findAll(xmlContent).map { it.groupValues[1].trim() }.toList()

            if (allVersions.isEmpty()) {
                return Pair(null, null)
            }

            // 根据分支类型过滤和选择版本
            when (branchType) {
                MavenVersionService.BranchType.QA -> {
                    // 筛选qa版本并选择最大的
                    val qaVersions = allVersions.filter { it.contains("-qa-", ignoreCase = true) }
                    latestVersion = getMaxVersion(qaVersions)

                    // Release版本仍然是正式版本
                    releaseVersion = getMaxReleaseVersion(allVersions)
                }

                MavenVersionService.BranchType.UAT -> {
                    // 筛选uat版本并选择最大的
                    val uatVersions = allVersions.filter { it.contains("-uat-", ignoreCase = true) }
                    latestVersion = getMaxVersion(uatVersions)

                    // Release版本仍然是正式版本
                    releaseVersion = getMaxReleaseVersion(allVersions)
                }

                else -> {
                    // 其他情况使用原逻辑
                    // 解析 <latest> 标签
                    val latestRegex = Regex("<latest>([^<]+)</latest>")
                    val latestMatch = latestRegex.find(xmlContent)
                    latestMatch?.let {
                        latestVersion = it.groupValues[1].trim()
                    }

                    // 解析 <release> 标签
                    val releaseRegex = Regex("<release>([^<]+)</release>")
                    val releaseMatch = releaseRegex.find(xmlContent)
                    releaseMatch?.let {
                        releaseVersion = it.groupValues[1].trim()
                    }

                    // 如果没有找到latest或release标签，从所有版本中选择
                    if (latestVersion == null) {
                        latestVersion = getMaxVersion(allVersions.filter { it.contains("SNAPSHOT", ignoreCase = true) })
                    }
                    if (releaseVersion == null) {
                        releaseVersion = getMaxReleaseVersion(allVersions)
                    }
                }
            }

            logger.info("解析版本信息 - Latest: $latestVersion, Release: $releaseVersion")
            return Pair(releaseVersion, latestVersion)
        } catch (e: Exception) {
            logger.warn("解析版本元数据失败", e)
            return Pair(null, null)
        }
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
     * 获取最大的Release版本（不包含SNAPSHOT的版本）
     */
    private fun getMaxReleaseVersion(versions: List<String>): String? {
        val releaseVersions = versions.filter { !it.contains("SNAPSHOT", ignoreCase = true) }
        return getMaxVersion(releaseVersions)
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

    /**
     * 获取当前项目的Maven项目列表
     */
    fun getMavenProjects(): List<MavenProject> {
        return getMavenProjectsManager().projects
    }

    /**
     * 检查指定前缀的项目版本
     */
    fun checkVersions(groupIdPrefix: String = "com.ly"): List<VersionInfo> {
        val results = mutableListOf<VersionInfo>()

        getMavenProjects().forEach { mavenProject ->
            val groupId = mavenProject.mavenId.groupId
            val artifactId = mavenProject.mavenId.artifactId
            val currentVersion = mavenProject.mavenId.version

            // 只检查指定前缀的groupId
            if (groupId != null && artifactId != null && groupId.startsWith(groupIdPrefix)) {
                try {
                    val (latestRelease, latestSnapshot) = getRemoteVersions(groupId, artifactId)
                    results.add(
                        VersionInfo(
                            groupId = groupId,
                            artifactId = artifactId,
                            latestRelease = latestRelease,
                            latestSnapshot = latestSnapshot,
                            currentVersion = currentVersion
                        )
                    )
                } catch (e: Exception) {
                    logger.warn("版本检查失败: $groupId:$artifactId", e)
                    results.add(
                        VersionInfo(
                            groupId = groupId,
                            artifactId = artifactId,
                            currentVersion = currentVersion
                        )
                    )
                }
            }
        }

        return results
    }

    /**
     * 获取当前项目的版本信息
     */
    fun getCurrentProjectVersions(): List<VersionInfo> {
        val results = mutableListOf<VersionInfo>()

        getMavenProjects().forEach { mavenProject ->
            val groupId = mavenProject.mavenId.groupId
            val artifactId = mavenProject.mavenId.artifactId
            val currentVersion = mavenProject.mavenId.version

            if (groupId != null && artifactId != null) {
                try {
                    val (latestRelease, latestSnapshot) = getRemoteVersions(groupId, artifactId)
                    results.add(
                        VersionInfo(
                            groupId = groupId,
                            artifactId = artifactId,
                            latestRelease = latestRelease,
                            latestSnapshot = latestSnapshot,
                            currentVersion = currentVersion
                        )
                    )
                } catch (e: Exception) {
                    logger.warn("获取项目版本信息失败: $groupId:$artifactId", e)
                    results.add(
                        VersionInfo(
                            groupId = groupId,
                            artifactId = artifactId,
                            currentVersion = currentVersion
                        )
                    )
                }
            }
        }

        return results
    }

    companion object {
        fun getInstance(project: Project): MavenRepositoryService {
            return project.getService(MavenRepositoryService::class.java)
        }
    }
}