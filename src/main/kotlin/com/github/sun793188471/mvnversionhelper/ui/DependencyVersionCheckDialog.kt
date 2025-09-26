package com.github.sun793188471.mvnversionhelper.ui

import com.github.sun793188471.mvnversionhelper.services.MavenRepositoryService
import com.github.sun793188471.mvnversionhelper.services.MavenVersionService
import com.github.sun793188471.mvnversionhelper.settings.MavenVersionHelperSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
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
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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

        // 删除整个控制面板，直接显示表格
        createTable()
        val scrollPane = JBScrollPane(dependencyTable)
        scrollPane.preferredSize = Dimension(750, 350)
        mainPanel.add(scrollPane, BorderLayout.CENTER)

        return mainPanel
    }

    private fun createTable() {
        val columnNames = arrayOf(
            "依赖",
            "当前版本",
            "最新SNAPSHOT",
            "最新RELEASE",
            "修改版本号"  // 新增列
        )

        tableModel = object : DefaultTableModel(columnNames, 0) {
            override fun isCellEditable(row: Int, column: Int): Boolean {
                return column == 4  // 只有修改版本号列可编辑
            }

            override fun getColumnClass(columnIndex: Int): Class<*> {
                return String::class.java
            }
        }

        dependencyTable = JBTable(tableModel)
        dependencyTable.autoResizeMode = JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
        dependencyTable.rowHeight = 30
        // 设置列宽
        // 设置列宽
        val columnModel = dependencyTable.columnModel
        columnModel.getColumn(0).preferredWidth = 300
        columnModel.getColumn(1).preferredWidth = 150
        columnModel.getColumn(2).preferredWidth = 150
        columnModel.getColumn(3).preferredWidth = 150
        columnModel.getColumn(4).preferredWidth = 200  // 修改版本号列
        // 添加双击事件监听器
        dependencyTable.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val row = dependencyTable.rowAtPoint(e.point)
                    val col = dependencyTable.columnAtPoint(e.point)
                    if (row >= 0 && col >= 0) {
                        val value = dependencyTable.getValueAt(row, col)?.toString() ?: ""
                        if (value.isNotEmpty()) {
                            copyToClipboard(value)
                        }
                    }
                }
            }
        })
    }

    private fun copyToClipboard(text: String) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val selection = StringSelection(text)
        clipboard.setContents(selection, selection)
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
                    val groupId = com.intellij.openapi.application.ReadAction.compute<String?, Throwable> {
                        depTag.findFirstSubTag("groupId")?.value?.text
                    }
                    val artifactId = com.intellij.openapi.application.ReadAction.compute<String?, Throwable> {
                        depTag.findFirstSubTag("artifactId")?.value?.text
                    }
                    val versionTag = com.intellij.openapi.application.ReadAction.compute<XmlTag, Throwable> {
                        depTag.findFirstSubTag("version")
                    }
                    var version = com.intellij.openapi.application.ReadAction.compute<String?, Throwable> {
                        versionTag?.value?.text
                    }

                    if (groupId != null && artifactId != null && groupId.startsWith(groupIdPrefix)) {
                        indicator.text = "检查 $groupId:$artifactId"
                        indicator.fraction = index.toDouble() / dependencyTags.size

                        val managedDep = parentDependencyManagement["$groupId:$artifactId"]
                        // 如果当前依赖没有版本号，从 dependencyManagement 中查找
                        if (version == null) {
                            version = managedDep?.version
                        }

                        // 解析版本号中的占位符
                        val resolvedVersion = resolveVersionPlaceholder(version, properties, managedDep)

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
                                    versionLocation = findVersionLocation(
                                        groupId,
                                        artifactId,
                                        version,
                                        properties,
                                        managedDep
                                    )
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
        val version: String?,
        val dependencyPomFile: XmlFile?,
        var relVersion: String?,
        var relPomFile: XmlFile?
    )

    private data class PropertyValue(
        val value: String?,
        val propertyFile: XmlFile
    )

    data class VersionLocation(
        val type: VersionLocationType,
        val propertyKey: String? = null,
        val locationPomFile: XmlFile? = null
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
                result["$groupId:$artifactId"] = ManagedDependency(groupId, artifactId, version, currentPom, null, null)
            }
        }
        return result
    }

    private fun parseAllProperties(pomFile: XmlFile, parentPomFile: XmlFile?): Map<String, PropertyValue> {
        val result = this.parseProperties(pomFile)
        var parentResult = this.parseProperties(parentPomFile)
        return result + parentResult
    }

    /**
     * 解析 POM 文件中的 properties
     */
    private fun parseProperties(pomFile: XmlFile?): Map<String, PropertyValue> {
        val result = mutableMapOf<String, PropertyValue>()
        if (pomFile == null) return result

        // 包装在 ReadAction 中
        return com.intellij.openapi.application.ReadAction.compute<Map<String, PropertyValue>, Throwable> {
            val rootTag = pomFile.rootTag ?: return@compute result
            val propertiesTag = rootTag.findFirstSubTag("properties")

            propertiesTag?.children?.forEach { child ->
                if (child is XmlTag) {
                    val key = child.name
                    val value = PropertyValue(child.value.text, pomFile)
                    result[key] = value
                }
            }

            // 添加内置属性
            val projectVersion = rootTag.findFirstSubTag("version")?.value?.text
            if (projectVersion != null) {
                result["project.version"] = PropertyValue(projectVersion, pomFile)
            }

            result
        }
    }

    private fun resolveVersionPlaceholder(
        version: String?,
        properties: Map<String, PropertyValue>,
        managedDep: ManagedDependency?
    ): String? {
        if (version == null) return null
        // 如果是占位符，解析匹配
        if (version.startsWith("\${") && version.endsWith("}")) {
            val propertyKey = version.substring(2, version.length - 1)
            val propertyValue = properties[propertyKey]
            // 如果不为空，代表能解析出来
            if (propertyValue != null && propertyValue.value != null) {
                managedDep?.relPomFile = propertyValue.propertyFile
                managedDep?.relVersion = propertyValue.value
                return propertyValue.value
            }
            return null
        }
        return null
    }

    private fun findVersionLocation(
        groupId: String,
        artifactId: String,
        version: String?,
        properties: Map<String, PropertyValue>,
        managedDep: ManagedDependency?
    ): VersionLocation {
        // 如果版本是占位符，定位到 properties
        if (version != null && version.startsWith("\${") && version.endsWith("}")) {
            val propertyKey = version.substring(2, version.length - 1)
            if (properties.containsKey(propertyKey)) {
                return VersionLocation(
                    VersionLocationType.PROPERTY,
                    propertyKey,
                    properties.get(propertyKey)?.propertyFile
                )
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
            VersionLocation(VersionLocationType.DEPENDENCY_DIRECT, null, pomFile)
        } else {
            VersionLocation(VersionLocationType.DEPENDENCY_MANAGEMENT, null, pomFile)
        }
    }

    private fun refreshTable() {
        try {
            tableModel.rowCount = 0
            dependencies.forEach { depInfo ->
                tableModel.addRow(
                    arrayOf(
                        "${depInfo.groupId}:${depInfo.artifactId}",
                        depInfo.currentVersion,
                        depInfo.latestSnapshot ?: "无",
                        depInfo.latestRelease ?: "无",
                        depInfo.currentVersion  // 默认显示当前版本，用户可编辑
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
        // 收集修改版本号列有变更的依赖
        val changedDependencies = mutableListOf<Triple<DependencyInfo, String, String>>()
        for (i in 0 until tableModel.rowCount) {
            val originalDep = dependencies[i]
            val newVersion = tableModel.getValueAt(i, 4) as String?
            val currentVersion = originalDep.currentVersion ?: ""

            if (null != newVersion && newVersion != currentVersion && newVersion.isNotBlank()) {
                changedDependencies.add(Triple(originalDep, currentVersion, newVersion))
            }
        }
        if (changedDependencies.isEmpty()) {
            ApplicationManager.getApplication().invokeLater {
                Messages.showWarningDialog(
                    project,
                    "没有检测到版本变更",
                    "依赖版本更新"
                )
            }
            return
        }

        var updateCount = 0
        val failedUpdates = mutableListOf<String>()

        changedDependencies.forEachIndexed { index, (dep, oldVersion, newVersion) ->
            if (this.updateDependencyVersion(dep, newVersion)) {
                updateCount++
            } else {
                failedUpdates.add("${dep.groupId}:${dep.artifactId}")
            }
        }

        val message = if (failedUpdates.isEmpty()) {
            "成功更新了 $updateCount 个依赖的版本"
        } else {
            "成功更新了 $updateCount 个依赖的版本\n\n失败的依赖:\n${failedUpdates.joinToString("\n")}"
        }
        ApplicationManager.getApplication().invokeLater {
            Messages.showInfoMessage(
                project,
                message,
                "依赖版本更新"
            )
        }
        super.doOKAction()
    }

    private fun updateDependencyVersion(dep: DependencyInfo, newVersion: String): Boolean {
        return try {
            if (dep != null && dep.versionLocation.locationPomFile != null) {
                when (dep.versionLocation.type) {
                    VersionLocationType.PROPERTY -> {
                        // 更新 properties 中的版本
                        dep.versionLocation.propertyKey?.let { propertyKey ->
                            this.updatePropertyVersion(dep.versionLocation.locationPomFile, propertyKey, newVersion)
                        }
                    }

                    VersionLocationType.DEPENDENCY_DIRECT -> {
                        // 直接更新 dependency 中的版本
                        versionService.updateDependencyVersion(
                            dep.versionLocation.locationPomFile!!,
                            dep.groupId,
                            dep.artifactId,
                            newVersion
                        )
                    }

                    VersionLocationType.DEPENDENCY_MANAGEMENT -> {
                        // 更新 dependencyManagement 中的版本
                        this.updateDependencyManagementVersion(
                            dep.versionLocation.locationPomFile!!,
                            dep.groupId,
                            dep.artifactId,
                            newVersion
                        )
                    }
                }
            } else {
                versionService.updateDependencyVersion(
                    dep.versionLocation.locationPomFile!!,
                    dep.groupId,
                    dep.artifactId,
                    newVersion
                )
            }
        } catch (e: Exception) {
            false
        } == true
    }

    private fun updatePropertyVersion(updatePom: XmlFile?, propertyKey: String, newVersion: String): Boolean {
        if (updatePom == null) return false
        val rootTag = updatePom.rootTag ?: return false
        val propertiesTag = rootTag.findFirstSubTag("properties") ?: return false

        val propertyTag = propertiesTag.findFirstSubTag(propertyKey) ?: return false

        return try {
            WriteCommandAction.runWriteCommandAction(project) {
                propertyTag.value.text = newVersion
            }
            true
        } catch (e: Exception) {
            logger.warn("Failed to update property version: $propertyKey", e)
            false
        }
    }

    private fun updateDependencyManagementVersion(
        updatePom: XmlFile,
        groupId: String,
        artifactId: String,
        newVersion: String
    ): Boolean {
        val rootTag = updatePom.rootTag ?: return false
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