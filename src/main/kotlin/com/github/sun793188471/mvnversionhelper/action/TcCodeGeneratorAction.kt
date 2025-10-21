package com.github.sun793188471.mvnversionhelper.action

import com.github.sun793188471.mvnversionhelper.ui.TcCodeGeneratorDialog
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

class TcCodeGeneratorAction : AnAction("TC Code Generator") {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val dialog = TcCodeGeneratorDialog(project)
        dialog.show()
    }

    override fun update(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT)
        e.presentation.isEnabledAndVisible = project != null
    }
}

