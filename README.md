# PDF Extractor

仓库：[AHzzql/pdf-workbench](https://github.com/AHzzql/pdf-workbench)，默认分支 `master`。

PDF 文本、表格、图片提取及加密、签名与水印工作台。默认游客使用，可选账户保存独立聊天记录。

## 目录

| 目录 | 内容 |
|---|---|
| backend/src | Java 实现、配置和测试 |
| frontend/src、frontend/tests | 前端实现和测试 |
| deploy | Windows 本机部署配置与内部工具 |
| backend/target | 忽略版本控制的构建与验证产物 |

## 本机运行

已构建程序在根目录使用 `workbench.cmd start` 启动，`workbench.cmd status` 查看状态，`workbench.cmd stop` 停止，`workbench.cmd restart` 重启。入口为 http://127.0.0.1:5173 。

`deploy/runtime`、`deploy/.env.local` 与容器数据卷保存本机运行状态和数据，不属于构建缓存。

## 从 GitHub 源码首次构建

需要 JDK 21+、Maven、Node.js/npm、PowerShell 7.2+ 和运行 Linux Engine 的 Docker Desktop。在项目根目录执行：

```text
npm.cmd --prefix frontend ci
npm.cmd --prefix frontend run build
mvn.cmd -f backend/pom.xml -DskipTests package
workbench.cmd start
```

首次构建先生成程序，支撑服务就绪后执行 `mvn.cmd -f backend/pom.xml test`、`npm.cmd --prefix frontend test`。集成测试使用实际本机支撑服务，源码初次构建跳过测试不等于测试通过。

仓库只保留应用源码、测试、构建配置和运行所需的部署文件；报告、截图、演示稿、生成工具、专项验收脚本、构建产物、依赖和本机配置不纳入版本控制。普通 PDF 工具最多处理 300 页，AI 阅读最多 200 页，上传 PDF 最大 50 MiB。
