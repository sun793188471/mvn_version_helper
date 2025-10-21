package com.github.sun793188471.mvnversionhelper.services

import com.intellij.openapi.project.Project

object TcCodeGeneratorService {
    fun generate(
        project: Project,
        projectName: String,
        path: String,
        methodName: String,
        author: String
    ) {
        try {
            // 直接调用 Kotlin 实现，不使用反射
            val generator = CodeGeneratorService()
            generator.generateForMethod(project,projectName, path, methodName, author)
        } catch (ex: Exception) {
            throw RuntimeException("代码生成失败", ex)
        }
    }
}
