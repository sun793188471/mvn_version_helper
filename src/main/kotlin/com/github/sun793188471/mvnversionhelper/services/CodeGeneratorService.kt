package com.github.sun793188471.mvnversionhelper.services

import com.intellij.openapi.project.Project
import freemarker.template.Configuration
import freemarker.template.Template
import io.ktor.utils.io.*
import java.io.File
import java.io.FileWriter
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.text.SimpleDateFormat
import java.util.*

class CodeGeneratorService {
    companion object {
        const val PACKAGE_COMMON_PREFIX = "com.ly.flight.intl."
        const val FACADE_REQUEST_PATH = ".facade.request."
        const val FACADE_RESPONSE_PATH = ".facade.response."
        const val FACADE_IMPL = ".facade.impl"
        const val PACKAGE_BIZ_MANAGER_PATH = ".biz.manager."
        const val FILE_APP_BIZ_PATH = "app/biz/src/main/java/"
        const val PACKAGE_FACADE_CONVERTER_PATH = ".facade.converter."
        const val FILE_APP_FACADE_IMPL_PATH = "app/facade-impl/src/main/java/"

        private val SERVICE_CONSTANTS_IMPORT_PATH: MutableMap<String, String> = HashMap()
        private val SERVICE_CONSTANTS_FILE_PATH: MutableMap<String, String> = HashMap()
        private val SERVICE_PROXY_PATH: MutableMap<String, String> = HashMap()
        private val SERVICE_CORE_PROXY_PATH: MutableMap<String, String> = HashMap()

        init {
            SERVICE_CONSTANTS_IMPORT_PATH["treasurecore"] =
                "com.ly.flight.intl.treasurecore.biz.constants.TreasureServiceConstants"
            SERVICE_CONSTANTS_IMPORT_PATH["refundcore"] =
                "com.ly.flight.intl.refundcore.biz.constants.gateway.RefundServiceConstants"
            SERVICE_CONSTANTS_IMPORT_PATH["changecore"] = "com.ly.flight.intl.changecore.model.ChangeServiceConstants"
            SERVICE_CONSTANTS_IMPORT_PATH["delaycore"] =
                "com.ly.flight.intl.delaycore.biz.constants.DelayServiceConstants"
            SERVICE_CONSTANTS_IMPORT_PATH["reversecore"] =
                "com.ly.flight.intl.reversecore.common.constant.GatewayConstant"
            SERVICE_CONSTANTS_IMPORT_PATH["reverseauxiliary"] =
                "com.ly.flight.intl.reverseauxiliary.biz.constants.ReverseAuxiliaryServiceConstants"

            SERVICE_CONSTANTS_FILE_PATH["treasurecore"] =
                "app/biz/src/main/java/com/ly/flight/intl/treasurecore/biz/constants/TreasureServiceConstants.java"
            SERVICE_CONSTANTS_FILE_PATH["refundcore"] =
                "app/biz/src/main/java/com/ly/flight/intl/refundcore/biz/constants/gateway/RefundServiceConstants.java"
            SERVICE_CONSTANTS_FILE_PATH["changecore"] =
                "app/model/src/main/java/com/ly/flight/intl/changecore/model/ChangeServiceConstants.java"
            SERVICE_CONSTANTS_FILE_PATH["delaycore"] =
                "app/biz/src/main/java/com/ly/flight/intl/delaycore/biz/constants/DelayServiceConstants.java"
            SERVICE_CONSTANTS_FILE_PATH["reversecore"] =
                "app/common/src/main/java/com/ly/flight/intl/reversecore/common/constant/GatewayConstant.java"
            SERVICE_CONSTANTS_FILE_PATH["reverseauxiliary"] =
                "app/biz/src/main/java/com/ly/flight/intl/reverseauxiliary/biz/constants/ReverseAuxiliaryServiceConstants.java"


            SERVICE_PROXY_PATH["treasurecore"] = "com.ly.flight.intl.treasurecore.facade.TreasureServiceProxy"
            SERVICE_PROXY_PATH["refundcore"] = "com.ly.flight.intl.refundcore.facade.RefundServiceProxy"
            SERVICE_PROXY_PATH["changecore"] = "com.ly.flight.intl.changecore.facade.ChangeServiceProxy"
            SERVICE_PROXY_PATH["delaycore"] = "com.ly.flight.intl.delaycore.facade.DelayServiceProxy"
            SERVICE_PROXY_PATH["reversecore"] = "com.ly.flight.intl.reversecore.facade.GatewayServiceFactory"
            SERVICE_PROXY_PATH["reverseauxiliary"] =
                "com.ly.flight.intl.reverseauxiliary.facade.ReverseAuxiliaryServiceProxy"


            SERVICE_CORE_PROXY_PATH["treasurecore"] =
                "com.ly.flight.intl.treasurecore.biz.gateway.TreasureCoreProxyService"
            SERVICE_CORE_PROXY_PATH["refundcore"] = "com.ly.flight.intl.refundcore.biz.gateway.RefundCoreProxyService"
            SERVICE_CORE_PROXY_PATH["changecore"] = "com.ly.flight.intl.changecore.biz.gateway.ChangeCoreProxyService"
            SERVICE_CORE_PROXY_PATH["delaycore"] = "com.ly.flight.intl.delaycore.biz.gateway.DelayCoreProxyService"
            SERVICE_CORE_PROXY_PATH["reversecore"] =
                "com.ly.flight.intl.reversecore.biz.gateway.ReverseCoreProxyService"
            SERVICE_CORE_PROXY_PATH["reverseauxiliary"] =
                "com.ly.flight.intl.reverseauxiliary.biz.gateway.ReverseAuxiliaryProxyService"
        }
    }

    private val cfg: Configuration = Configuration(Configuration.VERSION_2_3_31)

    init {
        cfg.setClassForTemplateLoading(CodeGeneratorService::class.java, "/templates")
        cfg.defaultEncoding = "UTF-8"
    }

    @Throws(Exception::class)
    fun generateForMethod(project: Project, projectName: String, path: String, methodName: String, author: String) {

        // 使用 project.basePath 构建绝对路径，避免依赖当前工作目录
        val projectBase = project.basePath ?: System.getProperty("user.dir")
        val facadeDir = Paths.get(
            projectBase,
            "app", "facade", "src", "main", "java", "com", "ly", "flight", "intl", projectName, "facade"
        ).toString()

        val dir = File(facadeDir)
        if (!dir.exists() || !dir.isDirectory) {
            throw RuntimeException("facade 目录不存在: $facadeDir")
        }

        var matchedFacadeFile: File? = null
        var matchedFacadePackage: String? = null
        var matchedFacadeName: String? = null

        val javaFiles = dir.listFiles { _, name -> name.endsWith(".java") } ?: arrayOf()
        for (f in javaFiles) {
            val lines = Files.readAllLines(f.toPath())
            val content = lines.joinToString("\n")
            if (content.contains("@Path(\"$path\")")) {
                matchedFacadeFile = f
                matchedFacadePackage = extractPackage(content, projectName)
                matchedFacadeName = f.name.removeSuffix(".java")
                break
            }
        }

        val facadeInterfaceClassName: String
        val baseName: String
        if (matchedFacadeFile != null) {
            facadeInterfaceClassName = matchedFacadeName!!
            baseName = facadeInterfaceClassName.replace(Regex("Facade$"), "")
        } else {
            baseName = toPascalCase(path.replace(Regex("config$"), ""))
            facadeInterfaceClassName = baseName + "Facade"
            matchedFacadePackage = PACKAGE_COMMON_PREFIX + projectName + ".facade"
        }

        val methodPascal = toPascalCase(methodName)
        val requestClassDtoName = "${methodPascal}RequestDTO"
        val responseClassDtoName = "${methodPascal}ResponseDTO"
        val requestClassVoName = "${methodPascal}RequestVO"
        val responseClassVoName = "${methodPascal}ResponseVO"

        val facadeImplClassName = "$facadeInterfaceClassName" + "Impl"
        val constantName = toUpperSnake("${baseName}_${methodName}")
        val facadeImplPackageName = PACKAGE_COMMON_PREFIX + projectName + FACADE_IMPL
        val validatorPackageName = PACKAGE_COMMON_PREFIX + projectName + ".facade.validator." + path
        val validateClassName = toPascalCase(path) + methodName + "Validator"
        val facadeMapperPackageName = PACKAGE_COMMON_PREFIX + projectName + ".facade.mapper." + path
        val mapperClassName = toPascalCase(path) + methodName + "Mapper"
        val converterPackageName = PACKAGE_COMMON_PREFIX + projectName + PACKAGE_FACADE_CONVERTER_PATH + path
        val convertClassName = toPascalCase(path) + methodName + "Converter"
        val managerClassName = toPascalCase(path) + methodName + "Manager"
        val managerPackageName = PACKAGE_COMMON_PREFIX + projectName + PACKAGE_BIZ_MANAGER_PATH + path

        val requestDtoPackage = PACKAGE_COMMON_PREFIX + projectName + FACADE_REQUEST_PATH + path
        val responseDtoPackage = PACKAGE_COMMON_PREFIX + projectName + FACADE_RESPONSE_PATH + path

        val requestVoPackage = PACKAGE_COMMON_PREFIX + projectName + ".biz.model.vo.request." + path
        val responseVoPackage = PACKAGE_COMMON_PREFIX + projectName + ".biz.model.vo.response." + path

        val model: MutableMap<String, Any> = HashMap()
        model["path"] = path
        model["methodName"] = methodName
        model["projectName"] = projectName
        model["date"] = SimpleDateFormat("yyyy-MM-dd").format(Date())
        model["author"] = author

        model["constantClassName"] = getLastPartBySplit(SERVICE_CONSTANTS_IMPORT_PATH[projectName], ".") ?: ""
        model["requestDtoClassName"] = requestClassDtoName
        model["responseDtoClassName"] = responseClassDtoName
        model["requestVoClassName"] = requestClassVoName
        model["responseVoClassName"] = responseClassVoName
        model["facadeInterfaceClassName"] = facadeInterfaceClassName
        model["facadeImplClassName"] = facadeImplClassName
        model["validatorClassName"] = validateClassName
        model["facadeMapperClassName"] = mapperClassName
        model["converterClassName"] = convertClassName
        model["managerClassName"] = managerClassName

        model["facadePackageName"] = matchedFacadePackage ?: ""
        model["facadeImplPackageName"] = facadeImplPackageName
        model["validatorPackageName"] = validatorPackageName
        model["facadeMapperPackageName"] = facadeMapperPackageName
        model["converterPackageName"] = converterPackageName
        model["managerPackageName"] = managerPackageName
        model["requestDtoPackageName"] = requestDtoPackage
        model["responseDtoPackageName"] = responseDtoPackage
        model["responseVoPackageName"] = responseVoPackage
        model["requestVoPackageName"] = requestVoPackage

        model["constantName"] = constantName
        model["constantPath"] = SERVICE_CONSTANTS_IMPORT_PATH[projectName] ?: ""

        model["facadeServiceName"] = transferToLowerFirst(facadeInterfaceClassName)
        model["serviceProxyPath"] = SERVICE_PROXY_PATH[projectName] ?: ""
        model["serviceProxyName"] = getLastPartBySplit(SERVICE_PROXY_PATH[projectName], ".") ?: ""
        model["serviceProxy"] = transferToLowerFirst(getLastPartBySplit(SERVICE_PROXY_PATH[projectName], ".") ?: "")
        model["serviceCoreProxyPath"] = SERVICE_CORE_PROXY_PATH[projectName] ?: ""
        model["serviceCoreProxyName"] = getLastPartBySplit(SERVICE_CORE_PROXY_PATH[projectName], ".") ?: ""

        val generatedDtoImports = generateAllImportDtos(
            projectBase,
            requestClassDtoName,
            responseClassDtoName,
            model,
            requestDtoPackage,
            responseDtoPackage
        )
        val generatedVoImports =
            generateAllImportVos(
                projectBase, requestClassVoName, responseClassVoName, model, requestVoPackage, responseVoPackage
            )

        model["customAllDtoImport"] = getResReqImportBlock(generatedDtoImports).toString()
        model["customAllVoImport"] = getResReqImportBlock(generatedVoImports).toString()
        model["customRequestDtoImport"] = getResReqImportBlock(listOf(generatedDtoImports[0])).toString()

        generateFacade(
            projectBase,
            methodName,
            matchedFacadeFile,
            generatedDtoImports,
            responseClassDtoName,
            requestClassDtoName,
            facadeInterfaceClassName,
            facadeDir,
            model
        )
        generateFacadeImpl(
            projectBase,
            projectName,
            methodName,
            facadeImplClassName,
            requestClassDtoName,
            responseClassDtoName,
            constantName,
            generatedDtoImports,
            model
        )
        addConstants(projectBase, projectName, constantName)
        generateValidator(projectBase, validateClassName, validatorPackageName, model)
        generateMapper(projectBase, mapperClassName, facadeMapperPackageName, model)
        generateConverter(projectBase, convertClassName, converterPackageName, model)
        generateManager(projectBase, managerClassName, managerPackageName, model)
    }

    private fun generateManager(
        projectBase: String,
        managerClassName: String,
        managerPackageName: String,
        model: Map<String, Any>
    ) {
        val implDir = projectBase + "/" + FILE_APP_BIZ_PATH + managerPackageName.replace(".", "/")
        try {
            val implTemplate = cfg.getTemplate("manager.ftl")
            writeTemplateToFile(implTemplate, model, "$implDir/$managerClassName.java")
        } catch (ignored: Exception) {
        }
    }

    private fun generateConverter(
        projectBase: String,
        convertClassName: String,
        converterPackageName: String,
        model: Map<String, Any>
    ) {
        val implDir = projectBase + "/" + FILE_APP_FACADE_IMPL_PATH + converterPackageName.replace(".", "/")
        try {
            val implTemplate = cfg.getTemplate("converter.ftl")
            writeTemplateToFile(implTemplate, model, "$implDir/$convertClassName.java")
        } catch (ignored: Exception) {
        }
    }

    private fun generateMapper(
        projectBase: String,
        mapperClassName: String,
        facadeMapperPackageName: String,
        model: Map<String, Any>
    ) {
        val implDir = projectBase + "/app/facade-impl/src/main/java/" + facadeMapperPackageName.replace(".", "/")
        try {
            val implTemplate = cfg.getTemplate("facadeMapper.ftl")
            writeTemplateToFile(implTemplate, model, "$implDir/$mapperClassName.java")
        } catch (ignored: Exception) {
        }
    }

    private fun generateAllImportVos(
        projectBase: String,
        requestVoClassName: String,
        responseVoClassName: String,
        model: Map<String, Any>,
        requestVoPackage: String,
        responseVoPackage: String
    ): List<String> {
        val generatedVoImports = ArrayList<String>()
        try {
            val requestTemplate = cfg.getTemplate("requestVO.ftl")
            writeTemplateToFile(
                requestTemplate,
                model,
                projectBase + "/" +
                        "app/biz/src/main/java/${requestVoPackage.replace(".", "/")}/$requestVoClassName.java"
            )
        } catch (ignored: Exception) {
        }
        generatedVoImports.add("$requestVoPackage.$requestVoClassName")

        try {
            val responseTemplate = cfg.getTemplate("responseVO.ftl")
            writeTemplateToFile(
                responseTemplate,
                model,
                projectBase + "/app/biz/src/main/java/${
                    responseVoPackage.replace(
                        ".",
                        "/"
                    )
                }/$responseVoClassName.java"
            )
        } catch (ignored: Exception) {
        }
        generatedVoImports.add("$responseVoPackage.$responseVoClassName")
        return generatedVoImports
    }

    private fun generateValidator(
        projectBase: String,
        validateClassName: String,
        validatorPackageName: String,
        model: Map<String, Any>
    ) {
        val implDir = projectBase + "/app/facade-impl/src/main/java/" + validatorPackageName.replace(".", "/")
        try {
            val implTemplate = cfg.getTemplate("validator.ftl")
            writeTemplateToFile(implTemplate, model, "$implDir/$validateClassName.java")
        } catch (ignored: Exception) {
        }
    }

    @Throws(Exception::class)
    private fun generateFacadeImpl(
        projectBase: String,
        projectName: String,
        methodName: String,
        facadeImplClassName: String,
        requestDtoClassName: String,
        responseDtoClassName: String,
        constantName: String,
        generatedDtoImports: List<String>,
        model: Map<String, Any>
    ) {
        val facadeImplPackageName = PACKAGE_COMMON_PREFIX + projectName + FACADE_IMPL
        val implDir = projectBase + "/app/facade-impl/src/main/java/" + facadeImplPackageName.replace(".", "/")
        val implFile = File("$implDir$facadeImplClassName.java")
        if (!implFile.exists()) {
            try {
                val implTemplate = cfg.getTemplate("facadeImpl.ftl")
                writeTemplateToFile(implTemplate, model, "$implDir/$facadeImplClassName.java")
            } catch (ignored: Exception) {
            }
        } else {
            var content = String(Files.readAllBytes(implFile.toPath()))
            val importBlock = StringBuilder()
            for (full in generatedDtoImports) {
                val impLine = "import $full;"
                if (!content.contains(impLine)) {
                    importBlock.append(impLine).append("\n")
                }
            }
            if (importBlock.isNotEmpty()) {
                val pkgEnd = content.indexOf(";\n", content.indexOf("package "))
                var insertPos = if (pkgEnd >= 0) pkgEnd + 2 else 0
                val firstImport = content.indexOf("import ")
                if (firstImport >= 0 && firstImport < insertPos) insertPos = firstImport
                content = content.substring(0, insertPos) + "\n" + importBlock + content.substring(insertPos)
            }

            val methodStr = buildFacadeImplMethodString(
                projectName,
                methodName,
                responseDtoClassName,
                requestDtoClassName,
                constantName
            )
            val lastBrace = content.lastIndexOf("}")
            if (lastBrace == -1) {
                throw RuntimeException("facade impl 文件缺少结束符号 }: ${implFile.absolutePath}")
            }
            val newContent = content.substring(0, lastBrace) + "\n\n" + methodStr + "\n" + content.substring(lastBrace)
            Files.write(
                implFile.toPath(),
                newContent.toByteArray(),
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            )
        }
    }

    private fun getResReqImportBlock(generatedDtoImports: List<String>): StringBuilder {
        val importBlock = StringBuilder()
        for (full in generatedDtoImports) {
            val impLine = "import $full;"
            importBlock.append(impLine).append("\n")
        }
        return importBlock
    }

    private fun buildFacadeImplMethodString(
        projectName: String,
        methodName: String,
        responseClassName: String,
        requestClassName: String,
        constantName: String
    ): String {
        val constantPath = SERVICE_CONSTANTS_IMPORT_PATH[projectName]
        val constantFileName = getLastPartBySplit(constantPath, ".")
        val sb = StringBuilder()
        sb.append("    /**\n")
        sb.append("     * generated method\n")
        sb.append("     * @param requestDTO\n")
        sb.append("     * @return\n")
        sb.append("     */\n")
        sb.append("    @Override\n")
        sb.append("    @GatewayLogCfg(logModule = ").append(constantFileName).append(".").append(constantName)
            .append(")\n")
        sb.append("    public ").append(responseClassName).append(" ").append(methodName).append("(")
            .append(requestClassName).append(" requestDTO) {\n")
        sb.append("        ").append(responseClassName).append(" responseDTO = new ").append(responseClassName)
            .append("();\n")
        sb.append("        responseDTO = (")
            .append(responseClassName)
            .append(") ")
            .append(transferToLowerFirst(getLastPartBySplit(SERVICE_PROXY_PATH[projectName], ".") ?: ""))
            .append(".invoke(")
            .append(constantFileName).append(".").append(constantName).append(",\n")
        sb.append("            requestDTO, responseDTO);\n")
        sb.append("        return responseDTO;\n")
        sb.append("    }\n")
        return sb.toString()
    }

    private fun getLastPartBySplit(s: String?, delimiter: String?): String? {
        if (s == null) return null
        if (delimiter.isNullOrEmpty()) return s
        val idx = s.lastIndexOf(delimiter)
        if (idx >= 0) {
            val tail = s.substring(idx + delimiter.length).trim()
            if (tail.isNotEmpty()) return tail
        }
        return s
    }

    private fun generateFacade(
        projectBase: String,
        methodName: String,
        matchedFacadeFile: File?,
        generatedDtoImports: List<String>,
        responseClassName: String,
        requestClassName: String,
        facadeInterfaceClassName: String,
        facadeDir: String,
        interfaceModel: Map<String, Any>
    ) {
        if (matchedFacadeFile != null) {
            try {
                var content = String(Files.readAllBytes(matchedFacadeFile.toPath()))
                val importBlock = StringBuilder()
                for (full in generatedDtoImports) {
                    val impLine = "import $full;"
                    if (!content.contains(impLine)) importBlock.append(impLine).append("\n")
                }
                if (importBlock.isNotEmpty()) {
                    val pkgEnd = content.indexOf(";\n", content.indexOf("package "))
                    var insertPos = if (pkgEnd >= 0) pkgEnd + 2 else 0
                    val firstImport = content.indexOf("import ")
                    if (firstImport >= 0 && firstImport < insertPos) insertPos = firstImport
                    content = content.substring(0, insertPos) + "\n" + importBlock + content.substring(insertPos)
                }

                val methodStr = buildFacadeMethodString(methodName, responseClassName, requestClassName)
                val lastBrace = content.lastIndexOf("}")
                if (lastBrace == -1) throw RuntimeException("facade 文件缺少结束符号 }: ${matchedFacadeFile.absolutePath}")
                val newContent =
                    content.substring(0, lastBrace) + "\n\n" + methodStr + "\n" + content.substring(lastBrace)
                Files.write(
                    matchedFacadeFile.toPath(),
                    newContent.toByteArray(),
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
                )
            } catch (e: Exception) {
            }
        } else {
            try {
                val implTemplate = cfg.getTemplate("facade.ftl")
                writeTemplateToFile(implTemplate, interfaceModel, "$facadeDir/$facadeInterfaceClassName.java")

                // 计算常用变量
                val facadePackage = (interfaceModel["facadePackageName"] as? String) ?: ""
                val interfaceFullName =
                    if (facadePackage.isNotBlank()) "$facadePackage.$facadeInterfaceClassName" else facadeInterfaceClassName
                val dsfBeanId = "dsf$facadeInterfaceClassName"
                val implBeanId = transferToLowerFirst(facadeInterfaceClassName)

                val xmlPath =
                    Paths.get(projectBase + "/app/facade-impl/src/main/resources/META-INF/spring/facade-impl-beans.xml")
                var xmlContent = String(Files.readAllBytes(xmlPath))
                // 如果已经存在相同的 dsf bean id，就跳过修改
                if (!xmlContent.contains("id=\"$dsfBeanId\"") && !xmlContent.contains("<ref bean=\"$dsfBeanId\"")) {
                    // 1) 在 <constructor-arg name="services"> 的 <set> 内末尾插入 <ref bean="dsf..."/>
                    val servicesAnchor = "<constructor-arg name=\"services\">"
                    val setStartIdx = xmlContent.indexOf(servicesAnchor)
                    if (setStartIdx >= 0) {
                        val setOpenIdx = xmlContent.indexOf("<set", setStartIdx)
                        val setCloseIdx = xmlContent.indexOf("</set>", setOpenIdx)
                        if (setOpenIdx >= 0 && setCloseIdx >= 0) {
                            // 插入在 </set> 之前，保留缩进
                            val insertPos = setCloseIdx
                            // 采用同文件已有缩进样式（默认 12-16 空格）
                            val refLine = "    <ref bean=\"$dsfBeanId\"/>"
                            xmlContent = xmlContent.substring(
                                0,
                                insertPos
                            ) + refLine + "\n" + xmlContent.substring(insertPos)
                        }
                    }

                    // 2) 在闭合 </beans> 之前追加对应的 <dubbo:service ...>
                    val dubboBlock = StringBuilder()
                    dubboBlock.append("\n    <dubbo:service id=\"").append(dsfBeanId)
                        .append("\" ref=\"").append(implBeanId).append("\"\n")
                        .append("                   interface=\"").append(interfaceFullName).append("\"\n")
                        .append("                   protocol=\"tcdsfrest\">\n")
                        .append("    </dubbo:service>\n")
                    val beansClose = "</beans>"
                    val beansIdx = xmlContent.lastIndexOf(beansClose)
                    if (beansIdx >= 0) {
                        xmlContent =
                            xmlContent.substring(0, beansIdx) + dubboBlock.toString() + xmlContent.substring(
                                beansIdx
                            )
                    } else {
                        // 如果没有找到 </beans>，追加到文件末尾
                        xmlContent = xmlContent + dubboBlock.toString()
                    }

                    // 写回到文件
                    Files.write(
                        xmlPath,
                        xmlContent.toByteArray(),
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                    )
                }
//                var xmlInterfaceConfigPath= $interfaceModel["facadePackageName"]+"."+$interfaceModel["facadeInterfaceClassName"]
//
//                "<dubbo:service id="dsfAirlineConfigFacade" ref="airlineConfigFacade"
//                interface="com.ly.flight.intl.treasurecore.facade.AirlineConfigFacade"
//                protocol="tcdsfrest">
//                </dubbo:service>"

            } catch (e: Exception) {
            }
        }
    }

    private fun generateAllImportDtos(
        projectBase: String,
        requestDtoClassName: String,
        responseDtoClassName: String,
        model: Map<String, Any>,
        requestDtoPackage: String,
        responseDtoPackage: String
    ): List<String> {
        val generatedDtoImports = ArrayList<String>()
        try {
            val requestTemplate = cfg.getTemplate("requestDTO.ftl")
            writeTemplateToFile(
                requestTemplate,
                model,
                projectBase + "/app/facade/src/main/java/${
                    requestDtoPackage.replace(
                        ".",
                        "/"
                    )
                }/$requestDtoClassName.java"
            )
        } catch (ignored: Exception) {
            println(ignored.message)
            ignored.printStack()
        }
        generatedDtoImports.add("$requestDtoPackage.$requestDtoClassName")

        try {
            val responseTemplate = cfg.getTemplate("responseDTO.ftl")
            writeTemplateToFile(
                responseTemplate,
                model,
                projectBase + "/app/facade/src/main/java/${
                    responseDtoPackage.replace(
                        ".",
                        "/"
                    )
                }/$responseDtoClassName.java"
            )
        } catch (ignored: Exception) {
        }
        generatedDtoImports.add("$responseDtoPackage.$responseDtoClassName")
        return generatedDtoImports
    }

    private fun buildFacadeMethodString(
        methodName: String,
        responseClassName: String,
        requestClassName: String
    ): String {
        val m = StringBuilder()
        m.append("    @POST\n")
        m.append("    @Consumes({ \"application/json; charset=UTF-8\" })\n")
        m.append("    @Produces({ \"application/json; charset=UTF-8\" })\n")
        m.append("    @Path(\"").append(methodName).append("\")\n")
        m.append("    ").append(responseClassName).append(" ").append(methodName).append("(@RequestBody ")
            .append(requestClassName).append(" requestDTO);\n")
        return m.toString()
    }

    private fun writeTemplateToFile(template: Template, dataModel: Map<String, Any>, outputFilePath: String) {
        val out = File(outputFilePath)
        val parent = out.parentFile
        if (!parent.exists()) parent.mkdirs()
        FileWriter(out).use { writer: Writer ->
            template.process(dataModel, writer)
        }
    }

    private fun extractPackage(fileContent: String, projectName: String): String {
        for (line in fileContent.split("\n")) {
            var l = line.trim()
            if (l.startsWith("package ")) {
                l = l.substring("package ".length).trim()
                if (l.endsWith(";")) l = l.substring(0, l.length - 1)
                return l
            }
        }
        return PACKAGE_COMMON_PREFIX + projectName + ".facade"
    }

    private fun toPascalCase(s: String): String {
        val parts = s.split(Regex("[\\._\\-]"))
        val sb = StringBuilder()
        for (p in parts) {
            if (p.isEmpty()) continue
            sb.append(p[0].uppercaseChar())
            if (p.length > 1) sb.append(p.substring(1))
        }
        return sb.toString()
    }

    private fun toUpperSnake(s: String): String {
        val sb = StringBuilder()
        var prev = '\u0000'
        for (c in s.toCharArray()) {
            if (c.isUpperCase() && sb.isNotEmpty() && prev.isLowerCase()) {
                sb.append('_')
            } else if (c == '-' || c == '.') {
                sb.append('_')
                prev = c
                continue
            }
            sb.append(c.uppercaseChar())
            prev = c
        }
        return sb.toString().replace(Regex("__+"), "_")
    }

    private fun transferToLowerFirst(s: String?): String {
        if (s == null || s.isEmpty()) return s ?: ""
        return s[0].lowercaseChar() + s.substring(1)
    }

    @Throws(Exception::class)
    private fun addConstants(projectBase: String, projectName: String, constantName: String) {
        val filePath = (projectBase + "/" + SERVICE_CONSTANTS_FILE_PATH[projectName])
        val constantsFile = File(filePath)
        if (constantsFile.exists()) {
            val lines = Files.readAllLines(Paths.get(filePath)).toMutableList()
            var lastBraceIndex = -1
            for (i in lines.indices.reversed()) {
                if (lines[i].trim() == "}") {
                    lastBraceIndex = i
                    break
                }
            }
            if (lastBraceIndex == -1) throw RuntimeException("无法找到 $filePath 类的结束符号 }")
            val constantDefinition = "    public static final String $constantName = \"$constantName\";"
            lines.add(lastBraceIndex, constantDefinition)
            Files.write(Paths.get(filePath), lines, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
        }
    }
}