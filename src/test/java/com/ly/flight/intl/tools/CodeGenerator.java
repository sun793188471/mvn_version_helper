package com.ly.flight.intl.tools;

import freemarker.template.Configuration;
import freemarker.template.Template;

import java.io.File;
import java.io.FileWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;

/**
 * @author yyy07386
 * @version : CodeGenerator.java, v 0.1 2025年09月17日 11:03 yyy07386 Exp $
 */
public class CodeGenerator {
    public static final String               PACKAGE_COMMON_PREFIX         = "com.ly.flight.intl.";
    public static final String               FACADE_REQUEST_PATH           = ".facade.request.";
    public static final String               FACADE_RESPONSE_PATH          = ".facade.response.";
    public static final String               FACADE_IMPL                   = ".facade.impl";
    public static final String               PACKAGE_BIZ_MANAGER_PATH      = ".biz.manager.";
    public static final String               FILE_APP_BIZ_PATH             = "app/biz/src/main/java/";
    public static final String               PACAKGE_FACADE_CONVERTER_PATH = ".facade.converter.";
    public static final String               FILE_APP_FACADE_IMPL_PATH     = "app/facade-impl/src/main/java/";
    private Configuration                    cfg;

    /**
     * 对应服务的constants导入路径
     */
    private static final Map<String, String> SERVICE_CONSTANTS_IMPORT_PATH = new HashMap<>();

    /**
     * 对应服务的constants文件路径
     */
    private static final Map<String, String> SERVICE_CONSTANTS_FILE_PATH   = new HashMap<>();

    /**
     * 对应服务的sevice proxy路径
     */
    private static final Map<String, String> SERVICE_PROXY_PATH            = new HashMap<>();

    /**
     * 对应服务的sevice core proxy路径
     */
    private static final Map<String, String> SERVICE_CORE_PROXY_PATH       = new HashMap<>();

    static {
        SERVICE_CONSTANTS_IMPORT_PATH.put("treasurecore", "com.ly.flight.intl.treasurecore.biz.constants.TreasureServiceConstants");
        SERVICE_CONSTANTS_IMPORT_PATH.put("reversebase", "com.ly.flight.intl.reversebase.biz.constants.ReverseBaseServiceConstants");
        SERVICE_CONSTANTS_IMPORT_PATH.put("refundcore", "");
        SERVICE_CONSTANTS_IMPORT_PATH.put("changecore", "");
        SERVICE_CONSTANTS_IMPORT_PATH.put("delaycore", "");
        SERVICE_CONSTANTS_IMPORT_PATH.put("reversecore", "");
        SERVICE_CONSTANTS_IMPORT_PATH.put("reverseauxiliary", "");
        SERVICE_PROXY_PATH.put("treasurecore", "com.ly.flight.intl.treasurecore.facade.TreasureServiceProxy");
        SERVICE_PROXY_PATH.put("refundcore", "");
        SERVICE_PROXY_PATH.put("changecore", "");
        SERVICE_PROXY_PATH.put("delaycore", "");
        SERVICE_PROXY_PATH.put("reversecore", "");
        SERVICE_PROXY_PATH.put("reverseauxiliary", "");
        SERVICE_CONSTANTS_FILE_PATH.put("treasurecore", "app/biz/src/main/java/com/ly/flight/intl/treasurecore/biz/constants/TreasureServiceConstants.java");
        SERVICE_CONSTANTS_FILE_PATH.put("refundcore", "");
        SERVICE_CONSTANTS_FILE_PATH.put("changecore", "");
        SERVICE_CONSTANTS_FILE_PATH.put("delaycore", "");
        SERVICE_CONSTANTS_FILE_PATH.put("reversecore", "");
        SERVICE_CONSTANTS_FILE_PATH.put("reverseauxiliary", "");
        SERVICE_CORE_PROXY_PATH.put("treasurecore", "com.ly.flight.intl.treasurecore.biz.gateway.TreasureCoreProxyService");
        SERVICE_CORE_PROXY_PATH.put("refundcore", "");
        SERVICE_CORE_PROXY_PATH.put("changecore", "");
        SERVICE_CORE_PROXY_PATH.put("delaycore", "");
        SERVICE_CORE_PROXY_PATH.put("reversecore", "");
        SERVICE_CORE_PROXY_PATH.put("reverseauxiliary", "");
    }

    public CodeGenerator() {
        // 配置 FreeMarker 模板
        cfg = new Configuration(Configuration.VERSION_2_3_31);
        cfg.setClassForTemplateLoading(CodeGenerator.class, "/templates");
        cfg.setDefaultEncoding("UTF-8");
    }

    /**
     * 根据需要实现的method的名称，生成对应的代码文件，包括：
     * Constant、Facade、FacadeImpl、Converter、Validator、DTO、VO、Manager
     * @param projectName 项目名称
     * @param path 方法的facade路径
     * @param methodName 需要实现的方法名称
     * @throws Exception ex
     */
    public void generateForMethod(String projectName, String path, String methodName, String author) throws Exception {
        // 根目录下的 facade 接口目录（固定约定）
        String facadeDir = "app/facade/src/main/java/com/ly/flight/intl/" + projectName + "/facade";
        File dir = new File(facadeDir);
        if (!dir.exists() || !dir.isDirectory()) {
            throw new RuntimeException("facade 目录不存在: " + facadeDir);
        }

        File matchedFacadeFile = null;
        String matchedFacadePackage = null;
        String matchedFacadeName = null;

        // 1. 查找包含 @Path("path") 的 facade 接口文件
        for (File f : Objects.requireNonNull(dir.listFiles((d, name) -> name.endsWith(".java")))) {
            List<String> lines = Files.readAllLines(f.toPath());
            String content = String.join("\n", lines);
            if (content.contains("@Path(\"" + path + "\")")) {
                matchedFacadeFile = f;
                matchedFacadePackage = this.extractPackage(content, projectName);
                matchedFacadeName = f.getName().replace(".java", "");
                break;
            }
        }

        // 计算类名相关
        String facadeInterfaceClassName;
        String baseName;
        if (matchedFacadeFile != null) {
            facadeInterfaceClassName = matchedFacadeName;
            baseName = facadeInterfaceClassName.replaceAll("Facade$", "");
        } else {
            // 未匹配到则根据 path 生成 Facade 名称（PascalCase）
            baseName = this.toPascalCase(path.replaceAll("config$", ""));
            facadeInterfaceClassName = baseName + "Facade";
            matchedFacadePackage = PACKAGE_COMMON_PREFIX + projectName + ".facade";
        }

        // 方法相关名
        String methodPascal = this.toPascalCase(methodName);
        String requestClassDtoName = methodPascal + "RequestDTO";
        String responseClassDtoName = methodPascal + "ResponseDTO";
        String requestClassVoName = methodPascal + "RequestVO";
        String responseClassVoName = methodPascal + "ResponseVO";

        // ------------------------代码生成区域------------------------
        // 准备模板数据模型
        String facadeImplClassName = facadeInterfaceClassName + "Impl";
        String constantName = this.toUpperSnake(baseName + "_" + methodName);
        String facadeImplPackageName = PACKAGE_COMMON_PREFIX + projectName + FACADE_IMPL;
        String validatorPackageName = PACKAGE_COMMON_PREFIX + projectName + ".facade.validator." + path;
        String validateClassName = this.toPascalCase(path) + methodName + "Validator";
        String facadeMapperPackageName = PACKAGE_COMMON_PREFIX + projectName + ".facade.mapper." + path;
        String mapperClassName = this.toPascalCase(path) + methodName + "Mapper";
        String converterPackageName = PACKAGE_COMMON_PREFIX + projectName + PACAKGE_FACADE_CONVERTER_PATH + path;
        String convertClassName = this.toPascalCase(path) + methodName + "Converter";
        String managerClassName = this.toPascalCase(path) + methodName + "Manager";
        String managerPackageName = PACKAGE_COMMON_PREFIX + projectName + PACKAGE_BIZ_MANAGER_PATH + path;
        // 计算 DTO 包和类名
        String requestDtoPackage = PACKAGE_COMMON_PREFIX + projectName + FACADE_REQUEST_PATH + path;
        String responseDtoPackage = PACKAGE_COMMON_PREFIX + projectName + FACADE_RESPONSE_PATH + path;
        // 计算 DTO 包和类名
        String requestVoPackage = PACKAGE_COMMON_PREFIX + projectName + ".biz.model.vo.request." + path;
        String responseVoPackage = PACKAGE_COMMON_PREFIX + projectName + ".biz.model.vo.response." + path;

        Map<String, Object> model = new HashMap<>();
        // 基础信息
        model.put("path", path);
        model.put("methodName", methodName);
        model.put("projectName", projectName);
        // yyyy-MM-dd 日期格式
        model.put("date", new java.text.SimpleDateFormat("yyyy-MM-dd").format(new Date()));
        model.put("author", author);

        // 类名
        model.put("constantClassName", this.getLastPartBySplit(SERVICE_CONSTANTS_IMPORT_PATH.get(projectName), "."));
        model.put("requestDtoClassName", requestClassDtoName);
        model.put("responseDtoClassName", responseClassDtoName);
        model.put("requestVoClassName", requestClassVoName);
        model.put("responseVoClassName", responseClassVoName);
        model.put("facadeInterfaceClassName", facadeInterfaceClassName);
        model.put("facadeImplClassName", facadeImplClassName);
        model.put("validatorClassName", validateClassName);
        model.put("facadeMapperClassName", mapperClassName);
        model.put("converterClassName", convertClassName);
        model.put("managerClassName", managerClassName);
        // 包名
        model.put("facadePackageName", matchedFacadePackage);
        model.put("facadeImplPackageName", facadeImplPackageName);
        model.put("validatorPackageName", validatorPackageName);
        model.put("facadeMapperPackageName", facadeMapperPackageName);
        model.put("converterPackageName", converterPackageName);
        model.put("managerPackageName", managerPackageName);
        model.put("requestDtoPackageName", requestDtoPackage);
        model.put("responseDtoPackageName", responseDtoPackage);
        model.put("responseVoPackageName", responseVoPackage);
        model.put("requestVoPackageName", requestVoPackage);
        // 常量
        model.put("constantName", constantName);
        model.put("constantPath", SERVICE_CONSTANTS_IMPORT_PATH.get(projectName));
        // 其他
        model.put("facadeServiceName", this.transferToLowerFirst(facadeInterfaceClassName));
        model.put("serviceProxyPath", SERVICE_PROXY_PATH.get(projectName));
        model.put("serviceProxyName", this.getLastPartBySplit(SERVICE_PROXY_PATH.get(projectName), "."));
        model.put("serviceProxy", this.transferToLowerFirst(this.getLastPartBySplit(SERVICE_PROXY_PATH.get(projectName), ".")));
        model.put("serviceCoreProxyPath", SERVICE_CORE_PROXY_PATH.get(projectName));
        model.put("serviceCoreProxyName", this.getLastPartBySplit(SERVICE_CORE_PROXY_PATH.get(projectName), "."));

        // 1、生成 DTO 并记录 imports
        List<String> generatedDtoImports = this.generateAllImportDtos(requestClassDtoName, responseClassDtoName, model, requestDtoPackage, responseDtoPackage);

        // 2、生成 VO 并记录 imports
        List<String> generatedVoImports = this.generateAllImportVos(requestClassVoName, responseClassVoName, model, requestVoPackage, responseVoPackage);

        // 自定义 DTO/VO import 块
        model.put("customAllDtoImport", this.getResReqImportBlock(generatedDtoImports).toString());
        model.put("customAllVoImport", this.getResReqImportBlock(generatedVoImports).toString());
        model.put("customRequestDtoImport", this.getResReqImportBlock(Collections.singletonList(generatedDtoImports.get(0))).toString());

        // 3. 如果匹配到接口文件，向接口中追加方法；否则创建新的接口文件
        this.generateFacade(methodName, matchedFacadeFile, generatedDtoImports, responseClassDtoName, requestClassDtoName, facadeInterfaceClassName, facadeDir, model);
        // 4. 生成 facadeImpl（若不存在则生成，若存在则在实现类中追加方法实现 - 这里简单创建新文件）
        this.generateFacadeImpl(projectName, methodName, facadeImplClassName, requestClassDtoName, responseClassDtoName, constantName, generatedDtoImports, model);
        // 5. 将常量添加到 TreasureServiceConstants 中
        this.addConstants(projectName, constantName);
        // 纯添加
        // 6. 生成 validator
        this.generateValidator(validateClassName, validatorPackageName, model);
        // 7、生成 Mapper
        this.generateMapper(mapperClassName, managerPackageName, model);
        // 8、生成 Converter
        this.generateConverter(convertClassName, converterPackageName, model);
        // 9、生成 Manager
        this.generateManager(managerClassName, managerPackageName, model);
    }

    /**
     * 生成 Manager
     */
    private void generateManager(String managerClassName, String managerPackageName, Map<String, Object> model) {
        String implDir = FILE_APP_BIZ_PATH + managerPackageName.replace(".", "/");
        try {
            Template implTemplate = cfg.getTemplate("template/manager.ftl");
            writeTemplateToFile(implTemplate, model, implDir + "/" + managerClassName + ".java");
        } catch (Exception e) {
        }
    }

    /**
     * 生成 Manager
     */
    private void generateConverter(String convertClassName, String converterPackageName, Map<String, Object> model) {

        String implDir = FILE_APP_FACADE_IMPL_PATH + converterPackageName.replace(".", "/");
        try {
            Template implTemplate = cfg.getTemplate("template/converter.ftl");
            writeTemplateToFile(implTemplate, model, implDir + "/" + convertClassName + ".java");
        } catch (Exception e) {
        }
    }

    /**
     * 生成 Mapper
     */
    private void generateMapper(String mapperClassName, String facadeMapperPackageName, Map<String, Object> model) {
        String implDir = "app/facade-impl/src/main/java/" + facadeMapperPackageName.replace(".", "/");
        try {
            Template implTemplate = cfg.getTemplate("template/facadeMapper.ftl");
            writeTemplateToFile(implTemplate, model, implDir + "/" + mapperClassName + ".java");
        } catch (Exception e) {
        }
    }

    /**
     * 生成全部的 VO 并返回需要 import 的类列表
     */
    private List<String> generateAllImportVos(String requestVoClassName, String responseClassVoName, Map<String, Object> model, String requestVoPackage, String responseVoPackage) {
        List<String> generatedVoImports = new ArrayList<>();

        try {
            Template requestTemplate = cfg.getTemplate("template/requestVO.ftl");
            writeTemplateToFile(requestTemplate, model, "app/biz/src/main/java/" + requestVoPackage.replace(".", "/") + "/" + requestVoClassName + ".java");
        } catch (Exception e) {
        }
        generatedVoImports.add(requestVoPackage + "." + requestVoClassName);
        try {
            Template responseTemplate = cfg.getTemplate("template/responseVO.ftl");
            writeTemplateToFile(responseTemplate, model, "app/biz/src/main/java/" + responseVoPackage.replace(".", "/") + "/" + responseClassVoName + ".java");
        } catch (Exception e) {
        }
        generatedVoImports.add(responseVoPackage + "." + responseClassVoName);
        return generatedVoImports;
    }

    /**
     * 生成 Validator 文件内容
     */
    private void generateValidator(String validateClassName, String validatorPackageName, Map<String, Object> model) {
        String implDir = "app/facade-impl/src/main/java/" + validatorPackageName.replace(".", "/");
        try {
            Template implTemplate = cfg.getTemplate("template/validator.ftl");
            writeTemplateToFile(implTemplate, model, implDir + "/" + validateClassName + ".java");
        } catch (Exception e) {
        }
    }

    /**
     * 生成 FacadeImpl 文件内容
     */
    private void generateFacadeImpl(String projectName, String methodName, String facadeImplClassName, String requestDtoClassName, String responseDtoClassName, String constantName,
                                    List<String> generatedDtoImports, Map<String, Object> model) throws Exception {

        String facadeImplPackageName = PACKAGE_COMMON_PREFIX + projectName + FACADE_IMPL;
        String implDir = "app/facade-impl/src/main/java/" + facadeImplPackageName.replace(".", "/");
        File implFile = new File(implDir + facadeImplClassName + ".java");
        // 为空，代表未创建过对应的facade接口，新增接口的实现类
        if (!implFile.exists()) {
            try {
                Template implTemplate = cfg.getTemplate("template/facadeImpl.ftl");
                writeTemplateToFile(implTemplate, model, implDir + "/" + facadeImplClassName + ".java");
            } catch (Exception e) {
            }
        } else {
            // impl 已存在：追加 import（request/response DTO）和方法实现（保持与模板一致）
            String content = new String(Files.readAllBytes(implFile.toPath()));
            // 1) 补充缺失的 DTO import
            StringBuilder importBlock = new StringBuilder();
            for (String full : generatedDtoImports) {
                String impLine = "import " + full + ";";
                if (!content.contains(impLine)) {
                    importBlock.append(impLine).append("\n");
                }
            }

            if (importBlock.length() > 0) {
                int pkgEnd = content.indexOf(";\n", content.indexOf("package "));
                int insertPos = pkgEnd >= 0 ? pkgEnd + 2 : 0;
                int firstImport = content.indexOf("import ");
                if (firstImport >= 0 && firstImport < insertPos) {
                    insertPos = firstImport;
                }
                content = content.substring(0, insertPos) + "\n" + importBlock + content.substring(insertPos);
            }

            // 2) 生成方法字符串并追加到类末尾（最后一个 '}' 之前）
            String methodStr = buildFacadeImplMethodString(projectName, methodName, responseDtoClassName, requestDtoClassName, constantName);
            int lastBrace = content.lastIndexOf("}");
            if (lastBrace == -1) {
                throw new RuntimeException("facade impl 文件缺少结束符号 }: " + implFile.getAbsolutePath());
            }
            String newContent = content.substring(0, lastBrace) + "\n\n" + methodStr + "\n" + content.substring(lastBrace);
            Files.write(implFile.toPath(), newContent.getBytes(), StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        }
    }

    /**
     * 获取DTO的import块
     */
    private StringBuilder getResReqImportBlock(List<String> generatedDtoImports) {
        StringBuilder importBlock = new StringBuilder();
        for (String full : generatedDtoImports) {
            String impLine = "import " + full + ";";
            importBlock.append(impLine).append("\n");
        }
        return importBlock;
    }

    /**
     * 生成与 facadeImpl.ftl 模板一致的方法实现字符串（插入到 impl 文件）
     */
    private String buildFacadeImplMethodString(String projectName, String methodName, String responseClassName, String requestClassName, String constantName) {
        String constantPath = SERVICE_CONSTANTS_IMPORT_PATH.get(projectName);
        String constantFileName = this.getLastPartBySplit(constantPath, ".");
        StringBuilder sb = new StringBuilder();
        sb.append("    /**\n");
        sb.append("     * generated method\n");
        sb.append("     * @param requestDTO\n");
        sb.append("     * @return\n");
        sb.append("     */\n");
        sb.append("    @Override\n");
        sb.append("    @GatewayLogCfg(logModule = ").append(constantFileName).append(".").append(constantName).append(")\n");
        sb.append("    public ").append(responseClassName).append(" ").append(methodName).append("(").append(requestClassName).append(" requestDTO) {\n");
        sb.append("        ").append(responseClassName).append(" responseDTO = new ").append(responseClassName).append("();\n");
        sb.append("        responseDTO = (")
          .append(responseClassName)
          .append(") ")
          .append(transferToLowerFirst(this.getLastPartBySplit(SERVICE_PROXY_PATH.get(projectName), ".")))
          .append(".invoke(")
          .append(constantFileName)
          .append(".")
          .append(constantName)
          .append(",\n");
        sb.append("            requestDTO, responseDTO);\n");
        sb.append("        return responseDTO;\n");
        sb.append("    }\n");
        return sb.toString();
    }

    /**
     * 通过分隔符获取最后一段非空字符串
     * @param s 原始字段
     * @param delimiter 分隔符
     * @return 最后一段非空字符串
     */
    private String getLastPartBySplit(String s, String delimiter) {
        if (s == null)
            return null;
        if (delimiter == null || delimiter.isEmpty()) {
            return s;
        }

        // 快速方式：取最后一次出现分隔符之后的子串
        int idx = s.lastIndexOf(delimiter);
        if (idx >= 0) {
            String tail = s.substring(idx + delimiter.length()).trim();
            if (!tail.isEmpty())
                return tail;
        }
        return s;
    }

    /**
     * 生成或更新 Facade 接口文件
     */
    private void generateFacade(String methodName, File matchedFacadeFile, List<String> generatedDtoImports, String responseClassName, String requestClassName,
                                String facadeInterfaceClassName, String facadeDir, Map<String, Object> interfaceModel) throws Exception {
        // 如果匹配到接口文件，向接口中追加方法；并确保 import 中包含已生成 DTO
        if (matchedFacadeFile != null) {
            String content = new String(Files.readAllBytes(matchedFacadeFile.toPath()));
            // 1) 补充缺失的 import（在 package 后或第一个 import 之前插入）
            StringBuilder importBlock = new StringBuilder();
            for (String full : generatedDtoImports) {
                String impLine = "import " + full + ";";
                if (!content.contains(impLine)) {
                    importBlock.append(impLine).append("\n");
                }
            }
            if (importBlock.length() > 0) {
                int pkgEnd = content.indexOf(";\n", content.indexOf("package "));
                int insertPos = pkgEnd >= 0 ? pkgEnd + 2 : 0;
                // 如果已有 import，插入到第一个 import 之前会更整洁
                int firstImport = content.indexOf("import ");
                if (firstImport >= 0 && firstImport < insertPos) {
                    insertPos = firstImport;
                }
                content = content.substring(0, insertPos) + "\n" + importBlock + content.substring(insertPos);
            }

            // 2) 插入方法声明
            String methodStr = this.buildFacadeMethodString(methodName, responseClassName, requestClassName);
            int lastBrace = content.lastIndexOf("}");
            if (lastBrace == -1) {
                throw new RuntimeException("facade 文件缺少结束符号 }: " + matchedFacadeFile.getAbsolutePath());
            }
            String newContent = content.substring(0, lastBrace) + "\n\n" + methodStr + "\n" + content.substring(lastBrace);
            Files.write(matchedFacadeFile.toPath(), newContent.getBytes(), StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } else {
            try {
                Template implTemplate = cfg.getTemplate("template/facade.ftl");
                writeTemplateToFile(implTemplate, interfaceModel, facadeDir + "/" + facadeInterfaceClassName + ".java");
            } catch (Exception e) {
            }
        }
    }

    /**
     * 生成 全部的DTO 并返回需要 import 的类列表
     */
    private List<String> generateAllImportDtos(String requestDtoClassName, String responseDtoClassName, Map<String, Object> model, String requestDtoPackage,
                                               String responseDtoPackage) {
        List<String> generatedDtoImports = new ArrayList<>();

        // 生成 RequestDTO
        try {
            Template requestTemplate = cfg.getTemplate("template/requestDTO.ftl");
            writeTemplateToFile(requestTemplate, model, "app/facade/src/main/java/" + requestDtoPackage.replace(".", "/") + "/" + requestDtoClassName + ".java");
        } catch (Exception e) {
        }
        generatedDtoImports.add(requestDtoPackage + "." + requestDtoClassName);

        try {
            Template responseTemplate = cfg.getTemplate("template/responseDTO.ftl");
            writeTemplateToFile(responseTemplate, model, "app/facade/src/main/java/" + responseDtoPackage.replace(".", "/") + "/" + responseDtoClassName + ".java");
        } catch (Exception e) {
        }
        generatedDtoImports.add(responseDtoPackage + "." + responseDtoClassName);
        return generatedDtoImports;
    }

    /**
     * 构造 Facade 方法字符串
     * @param methodName 方法名
     * @param responseClassName 返回值类名
     * @param requestClassName 请求参数类名
     * @return 方法字符串
     */
    private String buildFacadeMethodString(String methodName, String responseClassName, String requestClassName) {
        StringBuilder m = new StringBuilder();
        m.append("    @POST\n");
        m.append("    @Consumes({ \"application/json; charset=UTF-8\" })\n");
        m.append("    @Produces({ \"application/json; charset=UTF-8\" })\n");
        m.append("    @Path(\"").append(methodName).append("\")\n");
        m.append("    ").append(responseClassName).append(" ").append(methodName).append("(@RequestBody ").append(requestClassName).append(" requestDTO);\n");
        return m.toString();
    }

    /**
     * 模版生成结果写入文件
     */
    private void writeTemplateToFile(Template template, Map<String, Object> dataModel, String outputFilePath) throws Exception {
        File out = new File(outputFilePath);
        File parent = out.getParentFile();
        if (!parent.exists())
            parent.mkdirs();
        try (Writer writer = new FileWriter(out)) {
            template.process(dataModel, writer);
        }
    }

    /**
     * 提取包名
     */
    private String extractPackage(String fileContent, String projectName) {
        for (String line : fileContent.split("\n")) {
            line = line.trim();
            if (line.startsWith("package ")) {
                line = line.substring("package ".length()).trim();
                if (line.endsWith(";"))
                    line = line.substring(0, line.length() - 1);
                return line;
            }
        }
        return PACKAGE_COMMON_PREFIX + projectName + ".facade";
    }

    /**
     * 转化成帕斯卡格式
     * @param s 原始字符串
     * @return 转化后的字符串
     */
    private String toPascalCase(String s) {
        String[] parts = s.split("[\\._\\-]");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty())
                continue;
            sb.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1)
                sb.append(p.substring(1));
        }
        return sb.toString();
    }

    /**
     * 转化成大些驼峰格式
     * @param s 原始字符串
     * @return 转化后的字符串
     */
    private String toUpperSnake(String s) {
        StringBuilder sb = new StringBuilder();
        char prev = 0;
        for (char c : s.toCharArray()) {
            if (Character.isUpperCase(c) && sb.length() > 0 && Character.isLowerCase(prev)) {
                sb.append('_');
            } else if (c == '-' || c == '.') {
                sb.append('_');
                prev = c;
                continue;
            }
            sb.append(Character.toUpperCase(c));
            prev = c;
        }
        // replace multiple underscores
        return sb.toString().replaceAll("__+", "_");
    }

    /**
     * 首字母小写
     * @param s 原始字符串
     * @return 转化后的字符串
     */
    private String transferToLowerFirst(String s) {
        if (s == null || s.isEmpty())
            return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * 将常量添加到 常量 类中
     */
    private void addConstants(String projectName, String constantName) throws Exception {
        String filePath = SERVICE_CONSTANTS_FILE_PATH.get(projectName);
        File constantsFile = new File(filePath);
        if (constantsFile.exists()) {
            // 读取现有文件内容
            List<String> lines = Files.readAllLines(Paths.get(filePath));

            // 找到最后一个 } 的位置
            int lastBraceIndex = -1;
            for (int i = lines.size() - 1; i >= 0; i--) {
                if (lines.get(i).trim().equals("}")) {
                    lastBraceIndex = i;
                    break;
                }
            }
            if (lastBraceIndex == -1) {
                throw new RuntimeException("无法找到 " + filePath + " 类的结束符号 }");
            }
            // 在最后一个 } 之前插入新常量
            String constantDefinition = "    public static final String " + constantName + " = \"" + constantName + "\";";
            lines.add(lastBraceIndex, constantDefinition);
            // 将更新后的内容写回文件
            Files.write(Paths.get(filePath), lines, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        }
    }
}
