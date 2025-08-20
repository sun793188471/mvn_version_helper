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
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import java.awt.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

class VersionUpdateDialog(
    private val project: Project,
    private val versionService: MavenVersionService
) : DialogWrapper(project) {

    private val versionField = JBTextField(20)
    private val pomFileCheckboxes = mutableListOf<Pair<JBCheckBox, XmlFile>>()
    private lateinit var selectAllCheckbox: JBCheckBox
    private lateinit var listPanel: JPanel
    private lateinit var scrollPane: JBScrollPane
    private lateinit var projectVersionPanel: JPanel

    private val repositoryService = MavenRepositoryService.getInstance(project)
    private val logger = Logger.getInstance(VersionUpdateDialog::class.java)

    private var pomFiles = versionService.findPomFiles()
    private val realBranchName = versionService.getRealBranchName()
    private val branchType = versionService.getBranchType(realBranchName)
    private val currentProjectVersion = versionService.getCurrentProjectRemoteVersions(branchType, pomFiles)

    // 用于缓存版本信息，避免重复请求
    private val versionCache = ConcurrentHashMap<String, Pair<String?, String?>>()

    init {
        title = "Update Maven Version"
        init()
    }

    private fun loadPomFiles() {
        pomFileCheckboxes.clear()
        listPanel.removeAll()

        if (pomFiles.isEmpty()) {
            listPanel.add(JBLabel("未找到 pom.xml 文件"))
        } else {
            listPanel.layout = GridBagLayout()
            val gbc = GridBagConstraints()
            gbc.anchor = GridBagConstraints.WEST
            gbc.insets = Insets(2, 5, 2, 5)

            val versionInfoPanels = mutableListOf<Pair<XmlFile, JPanel>>()

            pomFiles.forEachIndexed { index, pomFile ->
                gbc.gridx = 0
                gbc.gridy = index
                gbc.weightx = 0.0

                val checkbox = JBCheckBox("${pomFile.virtualFile.path}", true)
                pomFileCheckboxes.add(Pair(checkbox, pomFile))
                checkbox.addActionListener { updateSelectAllCheckboxState() }
                listPanel.add(checkbox, gbc)

                // 添加检查依赖按钮
                gbc.gridx = 1
                gbc.weightx = 0.0
                val checkDepsButton = JButton("检查依赖")
                checkDepsButton.addActionListener {
                    val depDialog = DependencyVersionCheckDialog(project, pomFile, versionService)
                    depDialog.show()
                }
                listPanel.add(checkDepsButton, gbc)

                // 添加版本信息面板
                gbc.gridx = 2
                gbc.weightx = 1.0
                gbc.fill = GridBagConstraints.HORIZONTAL
                val versionInfoPanel = JPanel()
                versionInfoPanel.add(JBLabel("正在加载..."))
                listPanel.add(versionInfoPanel, gbc)

                versionInfoPanels.add(Pair(pomFile, versionInfoPanel))
            }

            updateSelectAllCheckboxState()

            // 如果版本信息还未加载，启动异步加载
            loadRemoteVersionsAsync(versionInfoPanels)
        }

        listPanel.revalidate()
        listPanel.repaint()
    }

    private fun loadRemoteVersionsAsync(versionInfoPanels: List<Pair<XmlFile, JPanel>>) {
        val task = object : Task.Backgroundable(project, "正在加载远端版本信息...", false) {
            override fun run(indicator: ProgressIndicator) {

                // 收集所有需要查询的模块信息
                val moduleInfoList = mutableListOf<Triple<String, String, Pair<XmlFile, JPanel>>>()

                versionInfoPanels.forEach { (pomFile, panel) ->
                    val rootTag = pomFile.rootTag
                    if (rootTag != null) {
                        val groupId = rootTag.findFirstSubTag("groupId")?.value?.text
                            ?: rootTag.findFirstSubTag("parent")?.findFirstSubTag("groupId")?.value?.text
                        val artifactId = rootTag.findFirstSubTag("artifactId")?.value?.text

                        if (groupId != null && artifactId != null) {
                            val cacheKey = "$groupId:$artifactId"
                            if (!versionCache.containsKey(cacheKey)) {
                                moduleInfoList.add(Triple(groupId, artifactId, pomFile to panel))
                            }
                        }
                    }
                }

                // 并行获取所有版本信息，但不直接更新UI
                val futures = moduleInfoList.mapIndexed { index, (groupId, artifactId, _) ->
                    CompletableFuture.supplyAsync {
                        try {
                            indicator.text = "正在获取 $groupId:$artifactId 版本信息"
                            indicator.fraction = index.toDouble() / moduleInfoList.size

                            val versions = repositoryService.getRemoteVersions(groupId, artifactId, branchType)
                            val cacheKey = "$groupId:$artifactId"
                            versionCache[cacheKey] = versions

                            return@supplyAsync Triple(groupId, artifactId, versions)
                        } catch (e: Exception) {
                            logger.warn("获取版本信息失败: $groupId:$artifactId", e)
                            return@supplyAsync null
                        }
                    }
                }

                CompletableFuture.allOf(*futures.toTypedArray())
                    .get(30, java.util.concurrent.TimeUnit.SECONDS)

                logger.info("版本信息获取完成，缓存大小: ${versionCache.size}")
                logger.info("正在更新UI面板")
                updateAllPanelsWithCachedData(versionInfoPanels)
            }

            override fun onCancel() {
                // 取消时不清理缓存，保留已获取的数据
                logger.info("用户取消了版本信息加载")
            }
        }
        ProgressManager.getInstance().run(task)
    }

    private fun updateAllPanelsWithCachedData(versionInfoPanels: List<Pair<XmlFile, JPanel>>) {
        versionInfoPanels.forEach { (pomFile, panel) ->
            updateVersionInfoPanel(pomFile, panel)
        }
    }

    private fun showErrorInAllPanels(versionInfoPanels: List<Pair<XmlFile, JPanel>>, errorMessage: String) {
        versionInfoPanels.forEach { (_, panel) ->
            showErrorInPanel(panel, errorMessage)
        }
    }

    private fun updateVersionInfoPanel(pomFile: XmlFile, versionInfoPanel: JPanel) {
        try {
            versionInfoPanel.removeAll()

            val rootTag = pomFile.rootTag
            if (rootTag != null) {
                val groupId = rootTag.findFirstSubTag("groupId")?.value?.text
                    ?: rootTag.findFirstSubTag("parent")?.findFirstSubTag("groupId")?.value?.text
                val artifactId = rootTag.findFirstSubTag("artifactId")?.value?.text

                if (groupId != null && artifactId != null) {
                    val cacheKey = "$groupId:$artifactId"
                    val versions = versionCache[cacheKey]

                    if (versions != null) {
                        updateVersionInfoPanelWithVersions(pomFile, versionInfoPanel, versions)
                    } else {
                        versionInfoPanel.add(JBLabel("等待加载..."))
                    }
                } else {
                    versionInfoPanel.add(JBLabel("无法获取项目信息"))
                }
            } else {
                versionInfoPanel.add(JBLabel("POM文件格式错误"))
            }

            versionInfoPanel.revalidate()
            versionInfoPanel.repaint()

        } catch (e: Exception) {
            logger.warn("更新版本信息面板失败: ${pomFile.virtualFile.path}", e)
            showErrorInPanel(versionInfoPanel, "更新失败")
        }
    }

    private fun updateVersionInfoPanelWithVersions(
        pomFile: XmlFile,
        versionInfoPanel: JPanel,
        versions: Pair<String?, String?>
    ) {
        try {
            versionInfoPanel.removeAll()

            val rootTag = pomFile.rootTag
            if (rootTag != null) {
                val groupId = rootTag.findFirstSubTag("groupId")?.value?.text
                    ?: rootTag.findFirstSubTag("parent")?.findFirstSubTag("groupId")?.value?.text
                val artifactId = rootTag.findFirstSubTag("artifactId")?.value?.text

                if (groupId != null && artifactId != null) {
                    val infoPanel = JPanel(FlowLayout(FlowLayout.LEFT))

                    // 显示远端版本信息
                    val (release, latest) = versions
                    infoPanel.add(JBLabel("最新版本"))
                    if (release != null) {
                        infoPanel.add(JBLabel("Release:$release"))
                    } else {
                        infoPanel.add(JBLabel("Release:无"))
                    }

                    infoPanel.add(JBLabel("|"))

                    if (latest != null) {
                        infoPanel.add(JBLabel("Snapshot:$latest"))
                    } else {
                        infoPanel.add(JBLabel("Snapshot:无"))
                    }
                    versionInfoPanel.add(infoPanel)
                } else {
                    versionInfoPanel.add(JBLabel("GroupId 或 ArtifactId 为空"))
                }
            } else {
                versionInfoPanel.add(JBLabel("POM文件格式错误"))
            }

            versionInfoPanel.revalidate()
            versionInfoPanel.repaint()

        } catch (e: Exception) {
            logger.warn("更新版本信息面板失败: ${pomFile.virtualFile.path}", e)
            showErrorInPanel(versionInfoPanel, "更新失败")
        }
    }

    private fun showErrorInPanel(panel: JPanel, message: String) {
        try {
            panel.removeAll()
            panel.add(JBLabel(message))
            panel.revalidate()
            panel.repaint()
        } catch (e: Exception) {
            logger.warn("显示错误信息失败", e)
        }
    }

    override fun doCancelAction() {
        // 取消时不清理缓存，保留已获取的数据用于下次打开
        super.doCancelAction()
    }

    private fun loadProjectVersionInfo() {
        try {
            val (groupId, artifactId) = versionService.getParentProjectInfo(pomFiles)

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
                Messages.showWarningDialog(
                    project,
                    "无法自动推荐版本号，请检查当前分支和远端版本信息",
                    "智能推荐"
                )
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
                Messages.showWarningDialog(
                    project,
                    "无法获取远端RELEASE版本",
                    "版本选择"
                )
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
                Messages.showWarningDialog(
                    project,
                    "无法获取远端SNAPSHOT版本",
                    "版本选择"
                )
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

        mainPanel.add(inputPanel, gbc)

        // 刷新按钮
        val refreshButton = JButton("刷新")
        refreshButton.addActionListener {
            refreshData()
        }
        inputPanel.add(refreshButton)

        // POM文件列表标题和全选按钮
        gbc.gridy = 3
        val listHeaderPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        listHeaderPanel.add(JBLabel("选择要更新的POM文件:"))

        selectAllCheckbox = JBCheckBox("全选", true)
        selectAllCheckbox.addActionListener {
            val selected = selectAllCheckbox.isSelected
            pomFileCheckboxes.forEach { (checkbox, _) ->
                checkbox.isSelected = selected
            }
        }
        listHeaderPanel.add(selectAllCheckbox)
        mainPanel.add(listHeaderPanel, gbc)

        // POM文件列表
        gbc.gridy = 4
        gbc.fill = GridBagConstraints.BOTH
        gbc.weighty = 1.0

        listPanel = JPanel()
        listPanel.layout = GridBagLayout()

        scrollPane = JBScrollPane(listPanel)
        scrollPane.preferredSize = Dimension(1200, 500)
        mainPanel.add(scrollPane, gbc)

        // 在界面创建完成后加载信息
        loadProjectVersionInfo()
        loadPomFiles()

        return mainPanel
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

                    // 重新获取项目版本信息
                    val refreshedProjectVersion = versionService.getCurrentProjectRemoteVersions(branchType, pomFiles)
                    logger.info("重新获取项目版本信息: Release=${refreshedProjectVersion.first}, Snapshot=${refreshedProjectVersion.second}")

                    indicator.text = "更新界面..."
                    indicator.fraction = 0.8

                    // 清空当前列表
                    pomFileCheckboxes.clear()
                    listPanel.removeAll()

                    // 重新加载项目版本信息面板
                    loadProjectVersionInfo()
                    // 重新加载 POM 文件列表
                    loadPomFiles()

                    // 刷新界面
                    listPanel.revalidate()
                    listPanel.repaint()
                    scrollPane.revalidate()
                    scrollPane.repaint()

                    indicator.fraction = 1.0
                    logger.info("数据刷新完成")

                    Messages.showInfoMessage(
                        project,
                        "数据刷新完成！\n扫描到 ${refreshedPomFiles.size} 个 POM 文件",
                        "刷新成功"
                    )
                } catch (e: Exception) {
                    logger.warn("刷新数据时发生错误", e)
                    Messages.showErrorDialog(
                        project,
                        "刷新数据时发生错误: ${e.message}",
                        "刷新失败"
                    )
                }
            }
        }

        ProgressManager.getInstance().run(task)
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
        if (pomFileCheckboxes.isEmpty()) return

        val allSelected = pomFileCheckboxes.all { it.first.isSelected }
        val noneSelected = pomFileCheckboxes.none { it.first.isSelected }

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

    override fun doOKAction() {
        val newVersion = versionField.text.trim()
        if (newVersion.isBlank()) {
            Messages.showWarningDialog(
                project,
                MyBundle.message("version.empty.warning"),
                MyBundle.message("version.update.title")
            )
            return
        }

        val selectedFiles = pomFileCheckboxes.filter { it.first.isSelected }.map { it.second }
        if (selectedFiles.isEmpty()) {
            Messages.showWarningDialog(
                project,
                "请至少选择一个POM文件",
                MyBundle.message("version.update.title")
            )
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

                ApplicationManager.getApplication().invokeLater {
                    val message = MyBundle.message("version.update.success", successCount, newVersion)
                    Messages.showInfoMessage(project, message, MyBundle.message("version.update.title"))
                    close(OK_EXIT_CODE)
                }
            }
        }

        ProgressManager.getInstance().run(task)
    }

    override fun getPreferredSize(): Dimension {
        return Dimension(800, 500)
    }
}