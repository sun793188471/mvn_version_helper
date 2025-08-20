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

    private val pomFiles = versionService.findPomFiles()
    private val realBranchName = versionService.getRealBranchName()
    private val branchType = versionService.getBranchType(realBranchName)
    private val currentProjectVersion = versionService.getCurrentProjectRemoteVersions(branchType)

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

                // 同步加载远端版本信息
                loadRemoteVersionInfo(pomFile, versionInfoPanel)
            }

            updateSelectAllCheckboxState()
        }

        listPanel.revalidate()
        listPanel.repaint()
    }

    private fun loadRemoteVersionInfo(pomFile: XmlFile, versionInfoPanel: JPanel) {
        try {
            val rootTag = pomFile.rootTag
            if (rootTag != null) {
                val groupId = rootTag.findFirstSubTag("groupId")?.value?.text
                    ?: rootTag.findFirstSubTag("parent")?.findFirstSubTag("groupId")?.value?.text
                val artifactId = rootTag.findFirstSubTag("artifactId")?.value?.text

                if (groupId != null && artifactId != null) {
                    val (latestRelease, latestSnapshot) = repositoryService.getRemoteVersions(groupId, artifactId)

                    versionInfoPanel.removeAll()
                    versionInfoPanel.layout = FlowLayout(FlowLayout.LEFT)

                    versionInfoPanel.add(JBLabel("远端版本 - "))

                    if (latestRelease != null) {
                        versionInfoPanel.add(JBLabel("RELEASE: $latestRelease"))
                    } else {
                        versionInfoPanel.add(JBLabel("RELEASE: 无"))
                    }

                    versionInfoPanel.add(JBLabel(" | "))

                    if (latestSnapshot != null) {
                        versionInfoPanel.add(JBLabel("SNAPSHOT: $latestSnapshot"))
                    } else {
                        versionInfoPanel.add(JBLabel("SNAPSHOT: 无"))
                    }
                } else {
                    versionInfoPanel.removeAll()
                    versionInfoPanel.add(JBLabel("无法获取模块信息"))
                }
            } else {
                versionInfoPanel.removeAll()
                versionInfoPanel.add(JBLabel("POM文件解析失败"))
            }

            versionInfoPanel.revalidate()
            versionInfoPanel.repaint()

        } catch (e: Exception) {
            logger.warn("获取远端版本信息失败", e)
            versionInfoPanel.removeAll()
            versionInfoPanel.add(JBLabel("获取远端版本失败"))
            versionInfoPanel.revalidate()
            versionInfoPanel.repaint()
        }
    }

    private fun loadProjectVersionInfo() {
        try {
            val (groupId, artifactId) = versionService.getParentProjectInfo(pomFiles)

            projectVersionPanel.removeAll()
            projectVersionPanel.layout = FlowLayout(FlowLayout.LEFT)

            if (groupId != null && artifactId != null) {
                projectVersionPanel.add(JBLabel("当前项目: $groupId:$artifactId"))
                projectVersionPanel.add(JBLabel(" | "))

                if (currentProjectVersion.second != null) {
                    projectVersionPanel.add(JBLabel("远端 RELEASE: ${currentProjectVersion.first}"))
                } else {
                    projectVersionPanel.add(JBLabel("远端 RELEASE: 无"))
                }

                projectVersionPanel.add(JBLabel(" | "))

                if (currentProjectVersion.first != null) {
                    projectVersionPanel.add(JBLabel("远端 SNAPSHOT:  ${currentProjectVersion.second}"))
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

        // 添加配置按钮
        val configBtn = JButton("配置")
        configBtn.addActionListener {
            val configDialog = ConfigurationDialog(project)
            if (configDialog.showAndGet()) {
                // 配置保存后重新加载POM文件列表
                loadPomFiles()
            }
        }
        inputPanel.add(configBtn)

        mainPanel.add(inputPanel, gbc)

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
        scrollPane.preferredSize = Dimension(700, 250)
        mainPanel.add(scrollPane, gbc)

        // 在界面创建完成后加载信息
        loadProjectVersionInfo()
        loadPomFiles()

        return mainPanel
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