package com.github.sun793188471.mvnversionhelper.ui

import com.github.sun793188471.mvnversionhelper.services.TcCodeGeneratorService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.*

class TcCodeGeneratorDialog(private val project: Project) : DialogWrapper(project) {

    private val authorField = JBTextField(20)
    private val pathField = JBTextField(20)
    private val methodNameField = JBTextField(20)
    private val projectNames = arrayOf(
        "treasurecore", "refundcore", "changecore", "delaycore", "reversecore", "reverseauxiliary"
    )
    private val projectCombo = JComboBox(projectNames)

    init {
        title = "TC Code Generator"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        val form = JPanel()
        form.layout = BoxLayout(form, BoxLayout.Y_AXIS)

        val row1 = JPanel(FlowLayout(FlowLayout.LEFT))
        row1.add(JBLabel("作者:"))
        row1.add(authorField)
        form.add(row1)

        val row2 = JPanel(FlowLayout(FlowLayout.LEFT))
        row2.add(JBLabel("方法dsf路径:"))
        row2.add(pathField)
        form.add(row2)

        val row3 = JPanel(FlowLayout(FlowLayout.LEFT))
        row3.add(JBLabel("方法名称:"))
        row3.add(methodNameField)
        form.add(row3)

        val row4 = JPanel(FlowLayout(FlowLayout.LEFT))
        row4.add(JBLabel("项目名称:"))
        row4.add(projectCombo)
        form.add(row4)

        panel.add(form, BorderLayout.CENTER)
        return panel
    }

    override fun doOKAction() {
        val author = authorField.text.trim()
        val path = pathField.text.trim()
        val methodName = methodNameField.text.trim()
        val projectName = projectCombo.selectedItem as? String ?: ""

        if (author.isBlank() || path.isBlank() || methodName.isBlank() || projectName.isBlank()) {
            Messages.showWarningDialog(project, "所有字段均为必填", "TC Code Generator")
            return
        }

        try {
            TcCodeGeneratorService.generate(project, projectName, path, methodName, author)
            Messages.showInfoMessage(project, "代码生成成功", "TC Code Generator")
            super.doOKAction()
        } catch (e: Exception) {
            Messages.showErrorDialog(project, "生成失败: ${e.message}", "TC Code Generator")
        }
    }
}

