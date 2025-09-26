# mvn_version_helper

<!-- Plugin description -->
Maven Version Helper 是一个 IntelliJ IDEA 插件，用于简化 Maven 项目的版本管理。

主要功能：
- 批量更新 POM 文件版本号
- 智能推荐版本号（基于分支类型）
- 检查和更新依赖版本
- 支持排除特定路径的 POM 文件
- 集成 Git 分支信息进行版本管理

支持的分支类型：
- Master/Release 分支：生成 RELEASE 版本
- QA 分支：生成 qa-SNAPSHOT 版本
- UAT 分支：生成 uat-SNAPSHOT 版本
- 开发分支：生成带任务号的 SNAPSHOT 版本
<!-- Plugin description end -->

## 安装和使用

1. 在 IntelliJ IDEA 中安装插件
2. 右键点击项目或编辑器，选择 "Update Maven Version"
3. 配置排除路径和版本检查设置
4. 使用智能推荐功能自动生成合适的版本号
5. 点击项目的POM文件列表中的检查按钮，可以检查子文件里对应的依赖项目的版本号情况，可以自定义输入新的版本号进行更新