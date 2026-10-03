# 开发状态

M0～M2 已实现：Maven/Spring Boot 工程、PageSelection、PdfTextUtils、PdfInfoUtils、PdfTableUtils 及数据模型和异常。表格支持水平垂直边框的规则网格、中文多行、空单元格、多表区域筛选和带公式防护的 CSV；CSV 先写临时文件，成功后替换输出并清理。JDK 25、Maven 3.9.15、编译目标 Java 21；最近 mvn test 为 32 项全部通过，M2 的 mvn package 已成功，后续 CSV 清理调整经测试通过。M1 已验证中英文、裁剪及四种旋转、双栏、搜索、权限、批注、书签循环和 XMP 外部实体拒绝。文本矩形为字体度量近似，复杂版式和非横排顺序有限；复杂表格和跨页拼接不支持。下一项为 M3 图片原始流与 PNG/JPG 导出。M3～M10、业务 HTTP 与前端尚未完成；旧需求 Word 上次因占用未清理。
