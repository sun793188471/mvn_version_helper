package com.github.sun793188471.mvnversionhelper.ui

import com.github.sun793188471.mvnversionhelper.settings.MavenVersionHelperSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.*

class ConfigurationDialog(
    private val project: Project
) : DialogWrapper(project) {

    private val excludePathsListModel = DefaultListModel<String>()
    private val excludePathsList = JBList(excludePathsListModel)
    private val newPathField = JBTextField(30)
    private val groupIdPrefixField = JBTextField(20)
    private val settings = MavenVersionHelperSettings.getInstance(project)

    init {
        title = "Maven Version Helper 配置"
        init()
        loadCurrentSettings()
    }

    private fun loadCurrentSettings() {
        excludePathsListModel.clear()
        settings.getExcludedPaths().forEach { path ->
            excludePathsListModel.addElement(path)
        }
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout())

        // 创建Tab面板
        val tabbedPane = JTabbedPane()
        tabbedPane.addTab("排除路径", createExcludePathPanel())
        tabbedPane.addTab("版本检查", createVersionCheckPanel())

        mainPanel.add(tabbedPane, BorderLayout.CENTER)

        return mainPanel
    }

    private fun createExcludePathPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        // 标题和说明
        val titlePanel = JPanel(FlowLayout(FlowLayout.LEFT))
        titlePanel.add(JBLabel("配置需要排除的 POM 文件路径"))
        panel.add(titlePanel, BorderLayout.NORTH)

        // 列表区域
        val listPanel = JPanel(BorderLayout())
        listPanel.add(JBLabel("排除路径列表:"), BorderLayout.NORTH)

        excludePathsList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        val scrollPane = JBScrollPane(excludePathsList)
        scrollPane.preferredSize = Dimension(400, 200)
        listPanel.add(scrollPane, BorderLayout.CENTER)

        // 操作按钮
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        val removeButton = JButton("删除选中")
        removeButton.addActionListener {
            val selectedIndex = excludePathsList.selectedIndex
            if (selectedIndex >= 0) {
                excludePathsListModel.removeElementAt(selectedIndex)
            }
        }
        buttonPanel.add(removeButton)
        listPanel.add(buttonPanel, BorderLayout.SOUTH)

        panel.add(listPanel, BorderLayout.CENTER)

        // 添加新路径区域
        val addPanel = JPanel(BorderLayout())
        addPanel.add(JBLabel("添加新的排除路径:"), BorderLayout.NORTH)

        val inputPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        inputPanel.add(JBLabel("路径:"))
        inputPanel.add(newPathField)

        val addButton = JButton("添加")
        addButton.addActionListener {
            val newPath = newPathField.text.trim()
            if (newPath.isNotBlank()) {
                if (!excludePathsListModel.contains(newPath)) {
                    excludePathsListModel.addElement(newPath)
                    newPathField.text = ""
                } else {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showWarningDialog(
                            project, "路径 '$newPath' 已存在", "重复路径"
                        )
                    }
                }
            }
        }
        inputPanel.add(addButton)

        addPanel.add(inputPanel, BorderLayout.CENTER)

        // 说明文本
        val helpPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        helpPanel.add(JBLabel("<html><small>说明: 以 '/' 开头的相对路径，如 '/dalgen', '/target' 等</small></html>"))
        addPanel.add(helpPanel, BorderLayout.SOUTH)

        panel.add(addPanel, BorderLayout.SOUTH)

        return panel
    }

    private fun createVersionCheckPanel(): JComponent {
        val panel = JPanel(BorderLayout())

        val configPanel = JPanel()
        configPanel.layout = BoxLayout(configPanel, BoxLayout.Y_AXIS)

        // GroupId前缀配置
        val prefixPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        prefixPanel.add(JBLabel("GroupId前缀:"))
        prefixPanel.add(groupIdPrefixField)
        configPanel.add(prefixPanel)

        // 说明
        val helpPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        helpPanel.add(JBLabel("<html><small>说明: 版本检查只会检查以此前缀开头的GroupId，默认为'com.ly'</small></html>"))
        configPanel.add(helpPanel)

        panel.add(configPanel, BorderLayout.NORTH)

        return panel
    }

    override fun doOKAction() {
        // 保存排除路径配置
        val paths = mutableListOf<String>()
        for (i in 0 until excludePathsListModel.size()) {
            paths.add(excludePathsListModel.getElementAt(i))
        }
        settings.setExcludedPaths(paths)

        // 保存GroupId前缀配置
        settings.setGroupIdPrefix(groupIdPrefixField.text.trim())
        ApplicationManager.getApplication().invokeLater {
            Messages.showInfoMessage(
                project, "配置已保存", "保存成功"
            )
        }
        super.doOKAction()
    }


    override fun getPreferredSize(): Dimension {
        return Dimension(600, 450)
    }
}