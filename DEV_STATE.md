# 开发状态

M0 工程初始化已完成：backend 的 Maven 配置、Spring Boot 启动、页码解析 PageSelection 和 ApplicationTest 已验证；JDK 25、Maven 3.9.15，编译目标 Java 21。mvn test 与 mvn package 均成功，13 项测试通过，包含真实随机端口 HTTP 的 /v3/api-docs 检查；生成 target/pdf-workbench-1.0.0.jar。沙箱内测试编译出现已生成类无法解析的问题，正常权限环境运行通过；PDFBox 的 commons-logging 已排除以使用 Spring 日志桥接。实施顺序见 docs/03-implementation-plan.md，下一项为 M1 文本、阅读顺序、搜索、书签、元数据及批注；其余 PDF 功能和前端尚未实现，旧需求 Word 清理仍待解除占用。
