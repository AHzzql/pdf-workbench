# PDF Extractor

PDF 文本、表格、图片提取及加密、签名与水印工作台。默认游客使用，可选账户保存独立聊天记录。

## 交付文件

- [英文 Part A](docs/part-a/submission/PartA_Submission.docx)
- [中文 Part B](docs/part-b/PartB_Submission.docx)
- [中文演示文稿（12 页）](docs/part-b/presentation/PartB_Demonstration.pptx)
- 本机软件与源码交付 ZIP：`backend/target/part-b-delivery/PDF_Extractor_PartB_Delivery.zip`（构建产物，不存入 Git）
- [运行说明](docs/part-b/DELIVERY_README.md)

## 目录

| 目录 | 内容 |
|---|---|
| backend/src | Java 实现、配置和测试 |
| frontend/src、frontend/tests | 前端实现和测试 |
| deploy | Windows 本机部署配置与内部工具 |
| docs | 正式报告、参考模板、设计材料与证据，见 [文档导航](docs/README.md) |
| tools | 文档、交付及性能验证工具 |
| backend/target | 忽略版本控制的构建与验证产物 |

## 本机运行

已构建程序在根目录使用 `workbench.cmd start` 启动，`workbench.cmd status` 查看状态，`workbench.cmd stop` 停止，`workbench.cmd restart` 重启。入口为 http://127.0.0.1:5173 。运行环境及构建步骤见运行说明。

`deploy/runtime`、`deploy/.env.local` 与容器数据卷保存本机运行状态和数据，不属于构建缓存。`DEV_STATE.md` 记录已验证状态及技术边界。

## 从 GitHub 源码首次构建

需要 JDK 21+、Maven、Node.js/npm、PowerShell 7.2+ 和运行 Linux Engine 的 Docker Desktop。在项目根目录执行：

```text
npm.cmd --prefix frontend ci
npm.cmd --prefix frontend run build
mvn.cmd -f backend/pom.xml -DskipTests package
workbench.cmd start
```

首次构建先生成程序，支撑服务就绪后执行 `mvn.cmd -f backend/pom.xml test`、`npm.cmd --prefix frontend test`。集成测试使用实际本机支撑服务，源码初次构建跳过测试不等于测试通过。
