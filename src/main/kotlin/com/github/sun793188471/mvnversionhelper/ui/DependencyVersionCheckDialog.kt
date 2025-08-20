package com.github.sun793188471.mvnversionhelper.ui

import com.github.sun793188471.mvnversionhelper.services.MavenRepositoryService
import com.github.sun793188471.mvnversionhelper.services.MavenVersionService
import com.github.sun793188471.mvnversionhelper.settings.MavenVersionHelperSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.psi.xml.XmlFile
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
        var selectRelease: Boolean = false
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

                dependencyTags.forEachIndexed { index, depTag ->
                    if (indicator.isCanceled) return

                    val groupId = depTag.findFirstSubTag("groupId")?.value?.text
                    val artifactId = depTag.findFirstSubTag("artifactId")?.value?.text
                    val version = depTag.findFirstSubTag("version")?.value?.text

                    if (groupId != null && artifactId != null && groupId.startsWith(groupIdPrefix)) {
                        indicator.text = "检查 $groupId:$artifactId"
                        indicator.fraction = index.toDouble() / dependencyTags.size

                        try {
                            val (latestRelease, latestSnapshot) = repositoryService.getRemoteVersions(
                                groupId,
                                artifactId
                            )
                            tempDependencies.add(
                                DependencyInfo(
                                    groupId = groupId,
                                    artifactId = artifactId,
                                    currentVersion = version,
                                    latestSnapshot = latestSnapshot,
                                    latestRelease = latestRelease
                                )
                            )
                        } catch (e: Exception) {
                            // 忽略获取失败的依赖
                        }
                    }
                }

                ApplicationManager.getApplication().invokeLater {
                    dependencies.clear()
                    dependencies.addAll(tempDependencies)
                    refreshTable()
                }
            }
        }

        ProgressManager.getInstance().run(task)
    }

    private fun refreshTable() {
        ApplicationManager.getApplication().invokeLater {
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

                ApplicationManager.getApplication().invokeLater {
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
        }

        ProgressManager.getInstance().run(task)
        super.doOKAction()
    }

    private fun updateDependencyVersion(groupId: String, artifactId: String, newVersion: String): Boolean {
        return try {
            versionService.updateDependencyVersion(pomFile, groupId, artifactId, newVersion)
        } catch (e: Exception) {
            false
        }
    }

    override fun getPreferredSize(): Dimension {
        return Dimension(800, 500)
    }
}