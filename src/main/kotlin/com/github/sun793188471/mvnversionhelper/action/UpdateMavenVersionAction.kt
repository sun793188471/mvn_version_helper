package com.github.sun793188471.mvnversionhelper.action

import com.github.sun793188471.mvnversionhelper.services.MavenVersionService
import com.github.sun793188471.mvnversionhelper.ui.VersionUpdateDialog
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service

class UpdateMavenVersionAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val versionService = project.service<MavenVersionService>()

        val dialog = VersionUpdateDialog(project, versionService)
        dialog.show()
    }

    override fun update(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT)
        e.presentation.isEnabledAndVisible = project != null
    }
}