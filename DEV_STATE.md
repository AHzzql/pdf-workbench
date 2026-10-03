# 开发状态

M0 与 M1 已实现：Maven/Spring Boot 工程、PageSelection、PdfTextUtils、PdfInfoUtils 及其模型和异常。文本支持分页、中英文、颜色字体字号、默认用户空间坐标、规则双栏、段落和 RE2/J 搜索；信息工具支持层级书签、Info/XMP、备注及高亮。JDK 25、Maven 3.9.15、编译目标 Java 21；mvn test 和 mvn package 成功，26 项测试通过，包括中文、裁剪及四种页面旋转、权限密码、XMP 外部实体拒绝、书签循环和真实 HTTP OpenAPI 启动。已修复空白页未触发 PDFTextStripper 回调的问题及 RE2/J 异常类型转换；Noto Sans SC 测试字体及 OFL 已纳入资源，FontBox 对 format 14 cmap 给出忽略警告，中文样本测试通过。坐标矩形为字体度量近似，复杂版式和非横排顺序不保证语义还原。核心文件位于 backend/src/main/java/cn/edu/pdfworkbench/util 与 model，测试位于 src/test；下一项为 M2 简单表格与 CSV。M3～M10、业务 HTTP 接口和 Vue 前端尚未完成；旧需求 Word 上次清理因文件占用失败。
