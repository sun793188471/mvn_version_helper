package com.github.sun793188471.mvnversionhelper.ui

import com.github.sun793188471.mvnversionhelper.services.MavenRepositoryService
import com.github.sun793188471.mvnversionhelper.services.MavenVersionService
import com.github.sun793188471.mvnversionhelper.settings.MavenVersionHelperSettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.DefaultTableModel

class DependencyVersionCheckDialog(
    private val project: Project,
    private val pomFile: XmlFile,
    private val parentPomFile: XmlFile?,
    private val versionService: MavenVersionService
) : DialogWrapper(project) {

    private val repositoryService = MavenRepositoryService.getInstance(project)
    private val settings = MavenVersionHelperSettings.getInstance(project)

    private lateinit var dependencyTable: JBTable
    private lateinit var tableModel: DefaultTableModel
    private lateinit var selectSnapshotCheckbox: JBCheckBox
    private lateinit var selectReleaseCheckbox: JBCheckBox

    private val dependencies = mutableListOf<DependencyInfo>()
    private val logger = Logger.getInstance(DependencyVersionCheckDialog::class.java)

    data class DependencyInfo(
        val groupId: String,
        val artifactId: String,
        val currentVersion: String?,
        var latestSnapshot: String?,
        var latestRelease: String?,
        var selectSnapshot: Boolean = false,
        var selectRelease: Boolean = false,
        var versionLocation: VersionLocation
    )

    init {
        title = "依赖版本检查 - ${pomFile.virtualFile.name}"
        setSize(800, 500)
        init()
        loadDependencies()
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout())

        // 顶部控制面板
        val controlPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        controlPanel.add(JBLabel("依赖列表:"))

        selectSnapshotCheckbox = JBCheckBox("全选 SNAPSHOT 版本")
        selectSnapshotCheckbox.addActionListener {
            val selected = selectSnapshotCheckbox.isSelected
            dependencies.forEach { dep ->
                if (dep.latestSnapshot != null) {
                    dep.selectSnapshot = selected
                    if (selected) dep.selectRelease = false
                }
            }
            refreshTable()
        }
        controlPanel.add(selectSnapshotCheckbox)

        selectReleaseCheckbox = JBCheckBox("全选 RELEASE 版本")
        selectReleaseCheckbox.addActionListener {
            val selected = selectReleaseCheckbox.isSelected
            dependencies.forEach { dep ->
                if (dep.latestRelease != null) {
                    dep.selectRelease = selected
                    if (selected) dep.selectSnapshot = false
                }
            }
            refreshTable()
        }
        controlPanel.add(selectReleaseCheckbox)

        mainPanel.add(controlPanel, BorderLayout.NORTH)

        // 表格
        createTable()
        val scrollPane = JBScrollPane(dependencyTable)
        scrollPane.preferredSize = Dimension(750, 350)
        mainPanel.add(scrollPane, BorderLayout.CENTER)

        return mainPanel
    }

    private fun createTable() {
        val columnNames = arrayOf(
            "GroupId", "ArtifactId", "当前版本",
            "最新 SNAPSHOT", "选择 SNAPSHOT",
            "最新 RELEASE", "选择 RELEASE"
        )

        tableModel = object : DefaultTableModel(columnNames, 0) {
            override fun isCellEditable(row: Int, column: Int): Boolean {
                return column == 4 || column == 6 // 只有选择列可编辑
            }

            override fun getColumnClass(columnIndex: Int): Class<*> {
                return when (columnIndex) {
                    4, 6 -> Boolean::class.java
                    else -> String::class.java
                }
            }
        }

        dependencyTable = JBTable(tableModel)
        dependencyTable.autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS

        // 设置列宽
        val columnModel = dependencyTable.columnModel
        columnModel.getColumn(0).preferredWidth = 150  // GroupId
        columnModel.getColumn(1).preferredWidth = 120  // ArtifactId
        columnModel.getColumn(2).preferredWidth = 80   // 当前版本
        columnModel.getColumn(3).preferredWidth = 100  // SNAPSHOT
        columnModel.getColumn(4).preferredWidth = 60   // 选择 SNAPSHOT
        columnModel.getColumn(5).preferredWidth = 100  // RELEASE
        columnModel.getColumn(6).preferredWidth = 60   // 选择 RELEASE

        // 添加复选框点击事件
        tableModel.addTableModelListener { e ->
            if (e.column == 4 || e.column == 6) {
                val row = e.firstRow
                if (row >= 0 && row < dependencies.size) {
                    val dep = dependencies[row]
                    when (e.column) {
                        4 -> { // SNAPSHOT 选择
                            dep.selectSnapshot = tableModel.getValueAt(row, 4) as Boolean
                            if (dep.selectSnapshot) {
                                dep.selectRelease = false
                                tableModel.setValueAt(false, row, 6)
                            }
                        }

                        6 -> { // RELEASE 选择
                            dep.selectRelease = tableModel.getValueAt(row, 6) as Boolean
                            if (dep.selectRelease) {
                                dep.selectSnapshot = false
                                tableModel.setValueAt(false, row, 4)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun loadDependencies() {
        val task = object : Task.Backgroundable(project, "正在检查依赖版本...", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true

                val rootTag = pomFile.rootTag ?: return
                val dependenciesTag = rootTag.findFirstSubTag("dependencies") ?: return
                val dependencyTags = dependenciesTag.findSubTags("dependency")

                val groupIdPrefix = settings.getGroupIdPrefix()
                val tempDependencies = mutableListOf<DependencyInfo>()

                // 解析父 POM 的 dependencyManagement 和 properties
                val parentDependencyManagement = parseAllDependencyManagement(pomFile, parentPomFile)
                val properties = parseAllProperties(pomFile, parentPomFile)

                dependencyTags.forEachIndexed { index, depTag ->
                    if (indicator.isCanceled) return

                    val groupId = depTag.findFirstSubTag("groupId")?.value?.text
                    val artifactId = depTag.findFirstSubTag("artifactId")?.value?.text
                    val versionTag = depTag.findFirstSubTag("version")
                    var version = versionTag?.value?.text

                    if (groupId != null && artifactId != null && groupId.startsWith(groupIdPrefix)) {
                        indicator.text = "检查 $groupId:$artifactId"
                        indicator.fraction = index.toDouble() / dependencyTags.size

                        // 如果当前依赖没有版本号，从 dependencyManagement 中查找
                        if (version == null) {
                            val managedDep = parentDependencyManagement["$groupId:$artifactId"]
                            version = managedDep?.version
                        }

                        // 解析版本号中的占位符
                        val resolvedVersion = resolveVersionPlaceholder(version, properties)

                        try {
                            val (latestRelease, latestSnapshot) = repositoryService.getRemoteVersions(
                                groupId,
                                artifactId
                            )
                            tempDependencies.add(
                                DependencyInfo(
                                    groupId = groupId,
                                    artifactId = artifactId,
                                    currentVersion = resolvedVersion,
                                    latestSnapshot = latestSnapshot,
                                    latestRelease = latestRelease,
                                    versionLocation = findVersionLocation(groupId, artifactId, version, properties)
                                )
                            )
                        } catch (e: Exception) {
                            // 忽略获取失败的依赖
                        }
                    }
                }

                dependencies.clear()
                dependencies.addAll(tempDependencies)
                refreshTable()
            }
        }

        ProgressManager.getInstance().run(task)
    }

    private data class ManagedDependency(
        val groupId: String,
        val artifactId: String,
        val version: String?
    )

    data class VersionLocation(
        val type: VersionLocationType,
        val propertyKey: String? = null
    )

    enum class VersionLocationType {
        DEPENDENCY_DIRECT,      // 直接在dependency中定义版本
        DEPENDENCY_MANAGEMENT,  // 在dependencyManagement中定义
        PROPERTY               // 在properties中定义
    }

    private fun parseAllDependencyManagement(
        currentPom: XmlFile?,
        parentPom: XmlFile?
    ): Map<String, ManagedDependency> {
        val result = this.parseCurrentDependencyManagement(currentPom);
        val parentResult = this.parseCurrentDependencyManagement(parentPom);
        return parentResult + result
    }

    private fun parseCurrentDependencyManagement(currentPom: XmlFile?): Map<String, ManagedDependency> {
        val result = mutableMapOf<String, ManagedDependency>()

        // 首先检查当前 POM 的 dependencyManagement
        if (currentPom == null) return result
        val rootTag = currentPom.rootTag ?: return result
        val depMgmtTag = rootTag.findFirstSubTag("dependencyManagement")
        val dependenciesTag = depMgmtTag?.findFirstSubTag("dependencies")

        dependenciesTag?.findSubTags("dependency")?.forEach { depTag ->
            val groupId = depTag.findFirstSubTag("groupId")?.value?.text
            val artifactId = depTag.findFirstSubTag("artifactId")?.value?.text
            val version = depTag.findFirstSubTag("version")?.value?.text

            if (groupId != null && artifactId != null) {
                result["$groupId:$artifactId"] = ManagedDependency(groupId, artifactId, version)
            }
        }
        return result
    }

    private fun parseAllProperties(pomFile: XmlFile, parentPomFile: XmlFile?): Map<String, String> {
        val result = this.parseProperties(pomFile)
        var parentResult = this.parseProperties(parentPomFile)
        return result + parentResult
    }

    private fun parseProperties(pomFile: XmlFile?): Map<String, String> {
        val result = mutableMapOf<String, String>()
        pomFile ?: return result
        val rootTag = pomFile.rootTag ?: return result
        val propertiesTag = rootTag.findFirstSubTag("properties")

        propertiesTag?.children?.forEach { child ->
            if (child is XmlTag) {
                val key = child.name
                val value = child.value?.text
                if (key != null && value != null) {
                    result[key] = value
                }
            }
        }

        // 添加内置属性
        val projectVersion = rootTag.findFirstSubTag("version")?.value?.text
        if (projectVersion != null) {
            result["project.version"] = projectVersion
        }

        return result
    }

    private fun resolveVersionPlaceholder(version: String?, properties: Map<String, String>): String? {
        if (version == null) return null

        return if (version.startsWith("\${") && version.endsWith("}")) {
            val propertyKey = version.substring(2, version.length - 1)
            properties[propertyKey] ?: version
        } else {
            version
        }
    }

    private fun findVersionLocation(
        groupId: String,
        artifactId: String,
        version: String?,
        properties: Map<String, String>
    ): VersionLocation {
        // 如果版本是占位符，定位到 properties
        if (version != null && version.startsWith("\${") && version.endsWith("}")) {
            val propertyKey = version.substring(2, version.length - 1)
            if (properties.containsKey(propertyKey)) {
                return VersionLocation(VersionLocationType.PROPERTY, propertyKey)
            }
        }

        // 检查是否在当前 POM 的 dependencies 中有版本定义
        val rootTag = pomFile.rootTag ?: return VersionLocation(VersionLocationType.DEPENDENCY_MANAGEMENT)
        val dependenciesTag = rootTag.findFirstSubTag("dependencies")
        val currentDep = dependenciesTag?.findSubTags("dependency")?.find { depTag ->
            val depGroupId = depTag.findFirstSubTag("groupId")?.value?.text
            val depArtifactId = depTag.findFirstSubTag("artifactId")?.value?.text
            depGroupId == groupId && depArtifactId == artifactId
        }

        return if (currentDep?.findFirstSubTag("version") != null) {
            VersionLocation(VersionLocationType.DEPENDENCY_DIRECT)
        } else {
            VersionLocation(VersionLocationType.DEPENDENCY_MANAGEMENT)
        }
    }

    private fun refreshTable() {
        try {
            tableModel.rowCount = 0
            dependencies.forEach { dep ->
                tableModel.addRow(
                    arrayOf(
                        dep.groupId,
                        dep.artifactId,
                        dep.currentVersion ?: "未知",
                        dep.latestSnapshot ?: "无",
                        dep.selectSnapshot,
                        dep.latestRelease ?: "无",
                        dep.selectRelease
                    )
                )
            }
            dependencyTable.revalidate()
            dependencyTable.repaint()
        } catch (e: Exception) {
            logger.warn("刷新依赖表格失败", e)
        }
    }

    override fun doOKAction() {
        val selectedDependencies = dependencies.filter {
            it.selectSnapshot || it.selectRelease
        }

        if (selectedDependencies.isEmpty()) {
            Messages.showWarningDialog(
                project,
                "请至少选择一个依赖进行更新",
                "依赖版本更新"
            )
            return
        }

        val task = object : Task.Backgroundable(project, "正在更新依赖版本...", true) {
            override fun run(indicator: ProgressIndicator) {
                var updateCount = 0
                val failedUpdates = mutableListOf<String>()

                selectedDependencies.forEachIndexed { index, dep ->
                    if (indicator.isCanceled) return

                    indicator.text = "更新 ${dep.groupId}:${dep.artifactId}"
                    indicator.fraction = index.toDouble() / selectedDependencies.size

                    val newVersion = when {
                        dep.selectSnapshot -> dep.latestSnapshot
                        dep.selectRelease -> dep.latestRelease
                        else -> null
                    }

                    if (newVersion != null) {
                        if (updateDependencyVersion(dep.groupId, dep.artifactId, newVersion)) {
                            updateCount++
                        } else {
                            failedUpdates.add("${dep.groupId}:${dep.artifactId}")
                        }
                    }
                }

                val message = if (failedUpdates.isEmpty()) {
                    "成功更新了 $updateCount 个依赖的版本"
                } else {
                    "成功更新了 $updateCount 个依赖的版本\n\n失败的依赖:\n${failedUpdates.joinToString("\n")}"
                }

                Messages.showInfoMessage(
                    project,
                    message,
                    "依赖版本更新"
                )
            }
        }

        ProgressManager.getInstance().run(task)
        super.doOKAction()
    }

    private fun updateDependencyVersion(groupId: String, artifactId: String, newVersion: String): Boolean {
        return try {
            val dep = dependencies.find { it.groupId == groupId && it.artifactId == artifactId }
            if (dep != null) {
                when (dep.versionLocation.type) {
                    VersionLocationType.PROPERTY -> {
                        // 更新 properties 中的版本
                        dep.versionLocation.propertyKey?.let { propertyKey ->
                            updatePropertyVersion(propertyKey, newVersion)
                        }
                    }

                    VersionLocationType.DEPENDENCY_DIRECT -> {
                        // 直接更新 dependency 中的版本
                        versionService.updateDependencyVersion(pomFile, groupId, artifactId, newVersion)
                    }

                    VersionLocationType.DEPENDENCY_MANAGEMENT -> {
                        // 更新 dependencyManagement 中的版本
                        updateDependencyManagementVersion(groupId, artifactId, newVersion)
                    }
                }
            } else {
                versionService.updateDependencyVersion(pomFile, groupId, artifactId, newVersion)
            }
        } catch (e: Exception) {
            false
        } == true
    }

    private fun updatePropertyVersion(propertyKey: String, newVersion: String): Boolean {
        val rootTag = pomFile.rootTag ?: return false
        val propertiesTag = rootTag.findFirstSubTag("properties") ?: return false

        val propertyTag = propertiesTag.findFirstSubTag(propertyKey)
        if (propertyTag != null) {
            propertyTag.value?.text = newVersion
            return true
        }
        return false
    }

    private fun updateDependencyManagementVersion(groupId: String, artifactId: String, newVersion: String): Boolean {
        val rootTag = pomFile.rootTag ?: return false
        val depMgmtTag = rootTag.findFirstSubTag("dependencyManagement") ?: return false
        val dependenciesTag = depMgmtTag.findFirstSubTag("dependencies") ?: return false

        val targetDep = dependenciesTag.findSubTags("dependency").find { depTag ->
            val depGroupId = depTag.findFirstSubTag("groupId")?.value?.text
            val depArtifactId = depTag.findFirstSubTag("artifactId")?.value?.text
            depGroupId == groupId && depArtifactId == artifactId
        }

        val versionTag = targetDep?.findFirstSubTag("version")
        if (versionTag != null) {
            versionTag.value?.text = newVersion
            return true
        }
        return false
    }

    override fun getPreferredSize(): Dimension {
        return Dimension(800, 500)
    }
}