package com.github.sun793188471.mvnversionhelper.ui

import com.github.sun793188471.mvnversionhelper.MyBundle
import com.github.sun793188471.mvnversionhelper.services.MavenVersionService
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

    private val versionField = JBTextField(20) // 增加输入框长度
    private val pomFileCheckboxes = mutableListOf<Pair<JBCheckBox, XmlFile>>()
    private lateinit var selectAllCheckbox: JBCheckBox

    private lateinit var listPanel: JPanel
    private lateinit var scrollPane: JBScrollPane

    init {
        title = "Update Maven Version"
        init()
    }

    private fun loadPomFiles() {
        val pomFiles = versionService.findPomFiles()
        pomFileCheckboxes.clear()
        // 清空现有的列表
        listPanel.removeAll()


        if (pomFiles.isEmpty()) {
            val noFilesLabel = JBLabel("未找到任何 pom.xml 文件")
            val labelGbc = GridBagConstraints()
            labelGbc.gridx = 0
            labelGbc.gridy = 0
            labelGbc.anchor = GridBagConstraints.CENTER
            labelGbc.fill = GridBagConstraints.HORIZONTAL
            labelGbc.weightx = 1.0
            labelGbc.insets = Insets(10, 10, 10, 10)
            listPanel.add(noFilesLabel, labelGbc)
        } else {
            pomFiles.forEachIndexed { index, pomFile ->
                val relativePath = pomFile.virtualFile.path.removePrefix(project.basePath ?: "")
                val currentVersion = versionService.getCurrentVersionString(pomFile)
                val displayText = if (currentVersion != null) {
                    "$relativePath (当前版本: $currentVersion)"
                } else {
                    "$relativePath (无版本信息)"
                }

                val checkbox = JBCheckBox(displayText, true)
                pomFileCheckboxes.add(checkbox to pomFile)

                val itemGbc = GridBagConstraints()
                itemGbc.gridx = 0
                itemGbc.gridy = index
                itemGbc.anchor = GridBagConstraints.WEST
                itemGbc.fill = GridBagConstraints.HORIZONTAL
                itemGbc.weightx = 1.0
                itemGbc.insets = Insets(2, 10, 2, 10)

                // 监听每个复选框状态变化，更新全选按钮状态
                checkbox.addActionListener {
                    updateSelectAllCheckboxState()
                }

                listPanel.add(checkbox, itemGbc)
            }
        }

        // 刷新界面
        listPanel.revalidate()
        listPanel.repaint()
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints()

        // 版本输入区域
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.insets = Insets(5, 5, 5, 5)
        gbc.weightx = 1.0

        val inputPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        inputPanel.add(JBLabel("新版本号:"))
        inputPanel.add(versionField)

        val autoBtn = JButton("自动建议版本号")
        autoBtn.addActionListener {
            val autoVersion = versionService.autoGenerateNewVersionByBranch()
            if (autoVersion != null) {
                versionField.text = autoVersion
            } else {
                Messages.showWarningDialog(
                    project,
                    MyBundle.message("auto.suggest.failed"),
                    "自动建议版本号"
                )
            }
        }
        inputPanel.add(autoBtn)

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
        gbc.gridy = 1
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
        gbc.gridy = 2
        gbc.fill = GridBagConstraints.BOTH
        gbc.weighty = 1.0

        listPanel = JPanel()
        listPanel.layout = GridBagLayout()

        scrollPane = JBScrollPane(listPanel)
        scrollPane.preferredSize = Dimension(600, 200)
        mainPanel.add(scrollPane, gbc)

        // 在界面创建完成后加载POM文件
        loadPomFiles()

        return mainPanel
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

        // 获取选中的POM文件
        val selectedPomFiles = pomFileCheckboxes
            .filter { it.first.isSelected }
            .map { it.second }

        if (selectedPomFiles.isEmpty()) {
            Messages.showWarningDialog(
                project,
                "请至少选择一个POM文件进行更新",
                MyBundle.message("version.update.title")
            )
            return
        }

        try {
            // 更新选中的pom.xml文件的版本
            var updateCount = 0
            val failedFiles = mutableListOf<String>()

            selectedPomFiles.forEach { pomFile ->
                if (versionService.updateVersion(pomFile, newVersion)) {
                    updateCount++
                } else {
                    failedFiles.add(pomFile.virtualFile.path)
                }
            }

            if (updateCount > 0) {
                val message = if (failedFiles.isEmpty()) {
                    MyBundle.message("version.update.success", updateCount, newVersion)
                } else {
                    "${
                        MyBundle.message(
                            "version.update.success",
                            updateCount,
                            newVersion
                        )
                    }\n\n失败的文件:\n${failedFiles.joinToString("\n")}"
                }

                Messages.showInfoMessage(
                    project,
                    message,
                    MyBundle.message("version.update.title")
                )
                super.doOKAction()
            } else {
                Messages.showErrorDialog(
                    project,
                    MyBundle.message("version.update.failed"),
                    MyBundle.message("version.update.title")
                )
            }

        } catch (e: Exception) {
            Messages.showErrorDialog(
                project,
                MyBundle.message("version.update.error", e.message ?: "Unknown error"),
                MyBundle.message("version.update.title")
            )
        }
    }

    override fun getPreferredSize(): Dimension {
        return Dimension(700, 400)
    }
}