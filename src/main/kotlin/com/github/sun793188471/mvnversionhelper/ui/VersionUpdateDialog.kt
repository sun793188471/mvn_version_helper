package com.github.sun793188471.mvnversionhelper.ui

import com.github.sun793188471.mvnversionhelper.MyBundle
import com.github.sun793188471.mvnversionhelper.services.MavenRepositoryService
import com.github.sun793188471.mvnversionhelper.services.MavenVersionService
import com.github.sun793188471.mvnversionhelper.services.MavenVersionService.BranchType
import com.intellij.openapi.application.ApplicationManager
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
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import java.awt.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import javax.swing.*
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableCellRenderer

class VersionUpdateDialog(
    private val project: Project,
    private val versionService: MavenVersionService
) : DialogWrapper(project) {

    private val versionField = JBTextField(20)
    private lateinit var selectAllCheckbox: JBCheckBox
    private lateinit var projectVersionPanel: JPanel
    private lateinit var pomTable: JBTable
    private lateinit var tableModel: DefaultTableModel

    private val repositoryService = MavenRepositoryService.getInstance(project)
    private val logger = Logger.getInstance(VersionUpdateDialog::class.java)

    private var pomFiles = versionService.findPomFiles()
    private var realBranchName = versionService.getRealBranchName()
    private var branchType = versionService.getBranchType(realBranchName)
    private var currentProjectVersion = versionService.getCurrentProjectRemoteVersions(branchType, pomFiles)
    private var parentFile: XmlFile? = null

    // 用于缓存版本信息，避免重复请求
    private val versionCache = ConcurrentHashMap<String, Pair<String?, String?>>()

    // 数据类用于存储POM文件信息
    data class PomFileInfo(
        val xmlFile: XmlFile,
        val path: String,
        val localVersion: String,
        var remoteSnapshot: String = "加载中...",
        var remoteRelease: String = "加载中...",
        var isSelected: Boolean = true
    )

    private val pomFileInfoList = mutableListOf<PomFileInfo>()

    init {
        title = "Update Maven Version"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints()
        gbc.insets = Insets(5, 5, 5, 5)

        // 分支信息面板
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        val branchInfoPanel = createBranchInfoPanel(realBranchName)
        mainPanel.add(branchInfoPanel, gbc)

        // 项目版本信息面板
        gbc.gridy = 1
        projectVersionPanel = JPanel()
        mainPanel.add(projectVersionPanel, gbc)

        // 版本输入区域
        gbc.gridy = 2
        val inputPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        inputPanel.add(JBLabel(MyBundle.message("version.update.new")))
        inputPanel.add(versionField)

        // 智能推荐按钮
        val recommendButton = JButton("智能推荐版本")
        recommendButton.addActionListener {
            val recommendedVersion =
                versionService.getRecommendedVersion(branchType, realBranchName, currentProjectVersion)

            if (recommendedVersion != null) {
                versionField.text = recommendedVersion
            } else {
                ApplicationManager.getApplication().invokeLater {
                    Messages.showWarningDialog(
                        project,
                        "无法自动推荐版本号，请检查当前分支和远端版本信息",
                        "智能推荐"
                    )
                }
            }
        }
        inputPanel.add(recommendButton)

        // 选择最新RELEASE版本按钮
        val latestReleaseButton = JButton("最新RELEASE")
        latestReleaseButton.addActionListener {
            val releaseVersion = currentProjectVersion.first
            if (releaseVersion != null) {
                versionField.text = releaseVersion
            } else {
                ApplicationManager.getApplication().invokeLater {
                    Messages.showWarningDialog(
                        project,
                        "无法获取远端RELEASE版本",
                        "版本选择"
                    )
                }
            }
        }
        inputPanel.add(latestReleaseButton)

        // 选择最新SNAPSHOT版本按钮
        val latestSnapshotButton = JButton("最新SNAPSHOT")
        latestSnapshotButton.addActionListener {
            val snapshotVersion = currentProjectVersion.second
            if (snapshotVersion != null) {
                versionField.text = snapshotVersion
            } else {
                ApplicationManager.getApplication().invokeLater {
                    Messages.showWarningDialog(
                        project,
                        "无法获取远端SNAPSHOT版本",
                        "版本选择"
                    )
                }
            }
        }
        inputPanel.add(latestSnapshotButton)

        // 修改配置按钮的处理逻辑
        val configBtn = JButton("配置")
        configBtn.addActionListener {
            val configDialog = ConfigurationDialog(project)
            configDialog.showAndGet()
        }
        inputPanel.add(configBtn)

        // 刷新按钮
        val refreshButton = JButton("刷新")
        refreshButton.addActionListener {
            refreshData()
        }
        inputPanel.add(refreshButton)

        mainPanel.add(inputPanel, gbc)

        // POM文件列表标题和全选按钮
        gbc.gridy = 3
        val listHeaderPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        listHeaderPanel.add(JBLabel("选择要更新的POM文件:"))

        selectAllCheckbox = JBCheckBox("全选", true)
        selectAllCheckbox.addActionListener {
            val selected = selectAllCheckbox.isSelected
            pomFileInfoList.forEach { it.isSelected = selected }
            refreshTable()
        }
        listHeaderPanel.add(selectAllCheckbox)
        mainPanel.add(listHeaderPanel, gbc)

        // POM文件表格
        gbc.gridy = 4
        gbc.fill = GridBagConstraints.BOTH
        gbc.weighty = 1.0

        createTable()
        val scrollPane = JBScrollPane(pomTable)
        scrollPane.preferredSize = Dimension(1200, 500)
        mainPanel.add(scrollPane, gbc)

        // 在界面创建完成后加载信息
        loadProjectVersionInfo()
        loadPomFiles()

        return mainPanel
    }

    override fun doOKAction() {
        val newVersion = versionField.text.trim()
        if (newVersion.isBlank()) {
            ApplicationManager.getApplication().invokeLater {
                Messages.showWarningDialog(
                    project,
                    MyBundle.message("version.empty.warning"),
                    MyBundle.message("version.update.title")
                )
            }
            return
        }

        val selectedFiles = pomFileInfoList.filter { it.isSelected }.map { it.xmlFile }
        if (selectedFiles.isEmpty()) {
            ApplicationManager.getApplication().invokeLater {
                Messages.showWarningDialog(
                    project,
                    "请至少选择一个POM文件",
                    MyBundle.message("version.update.title")
                )
            }
            return
        }

        val task = object : Task.Backgroundable(project, "正在更新版本...", true) {
            override fun run(indicator: ProgressIndicator) {
                var successCount = 0

                selectedFiles.forEachIndexed { index, pomFile ->
                    indicator.text = "更新文件 ${index + 1}/${selectedFiles.size}"
                    indicator.fraction = index.toDouble() / selectedFiles.size

                    if (versionService.updateVersion(pomFile, newVersion)) {
                        successCount++
                    }
                }

                val message = MyBundle.message("version.update.success", successCount, newVersion)
                ApplicationManager.getApplication().invokeLater {
                    Messages.showInfoMessage(project, message, MyBundle.message("version.update.title"))
                    close(OK_EXIT_CODE)
                }
            }
        }

        ProgressManager.getInstance().run(task)
    }

    override fun getPreferredSize(): Dimension {
        return Dimension(1200, 600)
    }

    /**
     * 刷新数据 - 清除缓存并重新加载所有数据
     */
    private fun refreshData() {
        val task = object : Task.Backgroundable(project, "正在刷新数据...", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "清除缓存..."
                    indicator.fraction = 0.1

                    // 清除版本缓存
                    versionCache.clear()
                    logger.info("已清除版本缓存")

                    indicator.text = "重新扫描 POM 文件..."
                    indicator.fraction = 0.3

                    // 重新获取 POM 文件列表
                    val refreshedPomFiles = versionService.findPomFiles()
                    pomFiles = refreshedPomFiles

                    logger.info("重新扫描到 ${refreshedPomFiles.size} 个 POM 文件")

                    indicator.text = "重新获取项目版本信息..."
                    indicator.fraction = 0.5

                    // 获取分支名称
                    realBranchName = versionService.getRealBranchName()
                    // 重新获取分支信息
                    branchType = versionService.getBranchType(realBranchName)

                    // 重新获取项目版本信息
                    val refreshedProjectVersion = versionService.getCurrentProjectRemoteVersions(branchType, pomFiles)
                    logger.info("重新获取项目版本信息: Release=${refreshedProjectVersion.first}, Snapshot=${refreshedProjectVersion.second}")

                    indicator.text = "更新界面..."
                    indicator.fraction = 0.8

                    // 重新加载项目版本信息面板
                    loadProjectVersionInfo()
                    // 重新加载 POM 文件列表
                    loadPomFiles()

                    indicator.fraction = 1.0
                    logger.info("数据刷新完成")

                    ApplicationManager.getApplication().invokeLater {
                        Messages.showInfoMessage(
                            project,
                            "数据刷新完成！\n扫描到 ${refreshedPomFiles.size} 个 POM 文件",
                            "刷新成功"
                        )
                    }
                } catch (e: Exception) {
                    logger.warn("刷新数据时发生错误", e)
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(
                            project,
                            "刷新数据时发生错误: ${e.message}",
                            "刷新失败"
                        )
                    }
                }
            }
        }

        ProgressManager.getInstance().run(task)
    }


    private fun loadProjectVersionInfo() {
        try {
            val (groupId, artifactId, parent) = versionService.getParentProjectInfo(pomFiles)
            parentFile = parent
            projectVersionPanel.removeAll()
            projectVersionPanel.layout = FlowLayout(FlowLayout.LEFT)

            if (groupId != null && artifactId != null) {
                projectVersionPanel.add(JBLabel("当前项目: $groupId:$artifactId"))
                projectVersionPanel.add(JBLabel(" | "))

                if (currentProjectVersion.first != null) {
                    projectVersionPanel.add(JBLabel("Release: ${currentProjectVersion.first}"))
                } else {
                    projectVersionPanel.add(JBLabel("Release: 无"))
                }

                projectVersionPanel.add(JBLabel(" | "))

                if (currentProjectVersion.second != null) {
                    projectVersionPanel.add(JBLabel("远端 SNAPSHOT: ${currentProjectVersion.second}"))
                } else {
                    projectVersionPanel.add(JBLabel("远端 SNAPSHOT: 无"))
                }
            } else {
                projectVersionPanel.add(JBLabel("无法获取当前项目版本信息"))
            }

            projectVersionPanel.revalidate()
            projectVersionPanel.repaint()
        } catch (e: Exception) {
            logger.warn("获取项目版本信息失败", e)
            projectVersionPanel.removeAll()
            projectVersionPanel.add(JBLabel("获取项目版本信息失败"))
            projectVersionPanel.revalidate()
            projectVersionPanel.repaint()
        }
    }

    private fun createBranchInfoPanel(realBranchName: String?): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT))
        panel.add(JBLabel("分支信息: "))

        if (realBranchName != null) {
            panel.add(JBLabel("当前分支: $realBranchName"))
            panel.add(JBLabel(" | "))
            panel.add(JBLabel("分支类型: ${branchType.displayName}"))

            // 如果是开发分支，显示任务号
            if (branchType == BranchType.TASK) {
                val taskNumber = versionService.extractTaskNumber(realBranchName)
                if (taskNumber != null) {
                    panel.add(JBLabel(" | "))
                    panel.add(JBLabel("任务号: $taskNumber"))
                }
            }
        } else {
            panel.add(JBLabel("无法获取当前分支信息"))
        }

        return panel
    }

    private fun updateSelectAllCheckboxState() {
        if (pomFileInfoList.isEmpty()) return

        val allSelected = pomFileInfoList.all { it.isSelected }
        val noneSelected = pomFileInfoList.none { it.isSelected }

        ApplicationManager.getApplication().invokeLater {
            when {
                allSelected -> {
                    selectAllCheckbox.isSelected = true
                    selectAllCheckbox.text = "全选"
                }

                noneSelected -> {
                    selectAllCheckbox.isSelected = false
                    selectAllCheckbox.text = "全选"
                }

                else -> {
                    selectAllCheckbox.isSelected = false
                    selectAllCheckbox.text = "部分选中"
                }
            }
        }
    }


    private fun loadPomFiles() {
        pomFileInfoList.clear()

        pomFiles.forEach { pomFile ->
            val localVersion = getLocalVersion(pomFile)
            val pomInfo = PomFileInfo(
                xmlFile = pomFile,
                path = pomFile.virtualFile.path,
                localVersion = localVersion
            )
            pomFileInfoList.add(pomInfo)
        }

        refreshTable()
        // 异步加载远端版本信息
        loadRemoteVersionsAsync()
    }

    private fun getLocalVersion(pomFile: XmlFile): String {
        val rootTag = pomFile.rootTag ?: return "未知"
        val versionTag = versionService.getCurrentVersion(pomFile)
        return versionTag?.value?.text ?: "未知"
    }

    private fun createTable() {
        val columnNames = arrayOf("选择", "POM位置", "本地版本", "远端SNAPSHOT", "远端RELEASE", "操作")

        tableModel = object : DefaultTableModel(columnNames, 0) {
            override fun isCellEditable(row: Int, column: Int): Boolean {
                return column == 0 || column == 5 // 选择列和操作列可编辑
            }

            override fun getColumnClass(columnIndex: Int): Class<*> {
                return when (columnIndex) {
                    0 -> Boolean::class.java  // 第一列返回Boolean类型
                    5 -> JButton::class.java  // 操作按钮列
                    else -> String::class.java
                }
            }
        }

        pomTable = JBTable(tableModel)
        // 改为按比例调整，让POM位置列可以自适应
        pomTable.autoResizeMode = JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS
        pomTable.rowHeight = 30


        // 设置列宽
        val columnModel = pomTable.columnModel
        columnModel.getColumn(0).preferredWidth = 10   // 选择
        columnModel.getColumn(1).preferredWidth = 500
        columnModel.getColumn(1).minWidth = 200  // 最小宽度
        columnModel.getColumn(2).preferredWidth = 100  // 本地版本
        columnModel.getColumn(3).preferredWidth = 100  // 远端SNAPSHOT
        columnModel.getColumn(4).preferredWidth = 100  // 远端RELEASE
        columnModel.getColumn(5).preferredWidth = 10   // 操作

        // 设置第一列为复选框渲染器
        columnModel.getColumn(0).cellRenderer = CheckBoxRenderer()
        columnModel.getColumn(0).cellEditor = DefaultCellEditor(JCheckBox())
        // 自定义操作列的渲染器和编辑器
        columnModel.getColumn(5).cellRenderer = ButtonRenderer()
        columnModel.getColumn(5).cellEditor = ButtonEditor()

        // 添加选择框变化监听
        tableModel.addTableModelListener { e ->
            if (e.column == 0) {
                val row = e.firstRow
                if (row >= 0 && row < pomFileInfoList.size) {
                    pomFileInfoList[row].isSelected = tableModel.getValueAt(row, 0) as Boolean
                    updateSelectAllCheckboxState()
                }
            }
        }
    }

    private fun refreshTable() {
        try {
            tableModel.rowCount = 0
            pomFileInfoList.forEach { pomInfo ->
                tableModel.addRow(
                    arrayOf(
                        pomInfo.isSelected,
                        pomInfo.path,
                        pomInfo.localVersion,
                        pomInfo.remoteSnapshot,
                        pomInfo.remoteRelease,
                        "检查依赖"
                    )
                )
            }
            pomTable.revalidate()
            pomTable.repaint()
            updateSelectAllCheckboxState()
        } catch (e: Exception) {
            logger.warn("刷新表格失败", e)
        }
    }

    private fun loadRemoteVersionsAsync() {
        val task = object : Task.Backgroundable(project, "正在加载远端版本信息...", false) {
            override fun run(indicator: ProgressIndicator) {
                // 收集所有需要查询的模块信息
                val moduleInfoList = mutableListOf<Triple<String, String, Int>>()
                pomFileInfoList.forEachIndexed { index, pomInfo ->
                    val rootTag = com.intellij.openapi.application.ReadAction.compute<XmlTag?, Throwable> {
                        pomInfo.xmlFile.rootTag
                    }
                    if (rootTag != null) {
                        val groupId = com.intellij.openapi.application.ReadAction.compute<String?, Throwable> {
                            rootTag.findFirstSubTag("groupId")?.value?.text
                                ?: rootTag.findFirstSubTag("parent")?.findFirstSubTag("groupId")?.value?.text
                        }
                        val artifactId = com.intellij.openapi.application.ReadAction.compute<String?, Throwable> {
                            rootTag.findFirstSubTag("artifactId")?.value?.text
                        }


                        if (groupId != null && artifactId != null) {
                            val cacheKey = "$groupId:$artifactId"
                            if (!versionCache.containsKey(cacheKey)) {
                                moduleInfoList.add(Triple(groupId, artifactId, index))
                            } else {
                                // 使用缓存数据立即更新
                                val versions = versionCache[cacheKey]!!
                                pomInfo.remoteRelease = versions.first ?: "无"
                                pomInfo.remoteSnapshot = versions.second ?: "无"
                                updateTableRow(index)
                            }
                        }
                    }
                }

                // 并行获取版本信息
                val futures = moduleInfoList.mapIndexed { taskIndex, (groupId, artifactId, pomIndex) ->
                    CompletableFuture.supplyAsync {
                        try {
                            indicator.text = "正在获取 $groupId:$artifactId 版本信息"
                            indicator.fraction = taskIndex.toDouble() / moduleInfoList.size

                            val versions = repositoryService.getRemoteVersions(groupId, artifactId, branchType)
                            val cacheKey = "$groupId:$artifactId"
                            versionCache[cacheKey] = versions

                            // 更新对应的pomInfo
                            val pomInfo = pomFileInfoList[pomIndex]
                            pomInfo.remoteRelease = versions.first ?: "无"
                            pomInfo.remoteSnapshot = versions.second ?: "无"

                            // 更新表格行
                            updateTableRow(pomIndex)

                            return@supplyAsync true
                        } catch (e: Exception) {
                            logger.warn("获取版本信息失败: $groupId:$artifactId", e)
                            // 更新为错误状态
                            val pomInfo = pomFileInfoList[pomIndex]
                            pomInfo.remoteRelease = "获取失败"
                            pomInfo.remoteSnapshot = "获取失败"
                            updateTableRow(pomIndex)
                            return@supplyAsync false
                        }
                    }
                }

                try {
                    CompletableFuture.allOf(*futures.toTypedArray())
                        .get(30, java.util.concurrent.TimeUnit.SECONDS)
                    logger.info("版本信息获取完成，缓存大小: ${versionCache.size}")
                } catch (e: Exception) {
                    logger.warn("获取版本信息超时或失败", e)
                }
            }
        }
        ProgressManager.getInstance().run(task)
    }

    private fun updateTableRow(rowIndex: Int) {
        try {
            if (rowIndex >= 0 && rowIndex < pomFileInfoList.size) {
                val pomInfo = pomFileInfoList[rowIndex]
                tableModel.setValueAt(pomInfo.remoteSnapshot, rowIndex, 3)
                tableModel.setValueAt(pomInfo.remoteRelease, rowIndex, 4)
            }
        } catch (e: Exception) {
            logger.warn("更新表格行失败: $rowIndex", e)
        }
    }

    // 复选框渲染器
    private inner class CheckBoxRenderer : TableCellRenderer {
        private val checkBox = JCheckBox()

        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            checkBox.isSelected = value as? Boolean ?: false
            checkBox.horizontalAlignment = SwingConstants.CENTER

            if (isSelected) {
                checkBox.background = table.selectionBackground
                checkBox.foreground = table.selectionForeground
            } else {
                checkBox.background = table.background
                checkBox.foreground = table.foreground
            }

            return checkBox
        }
    }

    // 按钮渲染器
    private inner class ButtonRenderer : JButton(), TableCellRenderer {
        override fun getTableCellRendererComponent(
            table: JTable?, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, column: Int
        ): Component {
            text = "检查依赖"
            return this
        }
    }

    // 按钮编辑器
    private inner class ButtonEditor : DefaultCellEditor(JCheckBox()) {
        private val button = JButton()
        private var isPushed = false
        private var currentRow = -1

        init {
            button.isOpaque = true
            button.addActionListener { fireEditingStopped() }
        }

        override fun getTableCellEditorComponent(
            table: JTable?, value: Any?, isSelected: Boolean, row: Int, column: Int
        ): Component {
            currentRow = row
            isPushed = true
            button.text = "检查依赖"
            return button
        }

        override fun getCellEditorValue(): Any {
            if (isPushed && currentRow >= 0 && currentRow < pomFileInfoList.size) {
                val pomInfo = pomFileInfoList[currentRow]
                val depDialog = DependencyVersionCheckDialog(
                    project, pomInfo.xmlFile, parentFile, versionService, branchType
                )
                depDialog.show()
            }
            isPushed = false
            return "检查依赖"
        }

        override fun stopCellEditing(): Boolean {
            isPushed = false
            return super.stopCellEditing()
        }
    }
}