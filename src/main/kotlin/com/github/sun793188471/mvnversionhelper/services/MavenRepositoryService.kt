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
    fun getRemoteVersions(groupId: String, artifactId: String): Pair<String?, String?> {
        val repositories = getRepositoryUrls()

        for (repoUrl in repositories) {
            try {
                val versions = getVersionsFromRepository(repoUrl, groupId, artifactId)
                if (versions.isNotEmpty()) {
                    val latestRelease = versions
                        .filter { !it.contains("-SNAPSHOT") }
                        .maxByOrNull { parseVersion(it) }

                    val latestSnapshot = versions
                        .filter { it.contains("-SNAPSHOT") }
                        .maxByOrNull { parseVersion(it) }

                    return Pair(latestRelease, latestSnapshot)
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
    private fun getVersionsFromRepository(repoUrl: String, groupId: String, artifactId: String): List<String> {
        try {
            val baseUrl = repoUrl.removeSuffix("/")
            val groupPath = groupId.replace(".", "/")
            val metadataUrl = "$baseUrl/$groupPath/$artifactId/maven-metadata.xml"

            val url = URL(metadataUrl)
            val connection = url.openConnection()
            connection.connectTimeout = 5000
            connection.readTimeout = 10000

            val xmlContent = connection.getInputStream().bufferedReader().use { it.readText() }

            // 解析XML获取版本信息
            return parseVersionsFromMetadata(xmlContent)

        } catch (e: Exception) {
            logger.debug("无法从 $repoUrl 获取 $groupId:$artifactId 的元数据", e)
            return emptyList()
        }
    }

    /**
     * 解析maven-metadata.xml中的版本信息
     */
    private fun parseVersionsFromMetadata(xmlContent: String): List<String> {
        val versions = mutableListOf<String>()

        try {
            // 简单的XML解析，查找<version>标签
            val versionRegex = Regex("<version>([^<]+)</version>")
            val matches = versionRegex.findAll(xmlContent)

            matches.forEach { match ->
                val version = match.groupValues[1].trim()
                if (version.isNotBlank()) {
                    versions.add(version)
                }
            }
        } catch (e: Exception) {
            logger.warn("解析版本元数据失败", e)
        }

        return versions
    }

    /**
     * 简单的版本号解析比较
     */
    private fun parseVersion(version: String): String {
        return version
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