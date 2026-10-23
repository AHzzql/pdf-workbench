# PDF Extractor — Part B 交付包

## 主要材料

| 材料 | 路径 |
|---|---|
| Part A 英文需求与设计 | docs/part-a/submission/PartA_Submission.docx |
| Part B 中文设计与测试报告 | docs/part-b/PartB_Submission.docx |
| 中文演示文稿（12 页） | docs/part-b/presentation/PartB_Demonstration.pptx |
| 核心测试输出、截图及样本 | docs/part-b/evidence/ |
| 可执行 Spring Boot 程序 | backend/target/pdf-workbench-1.1.0.jar |
| 前端静态发布文件 | frontend/dist/ |
| Java 实现及 JUnit 源码 | backend/src/main/、backend/src/test/ |
| 前端实现与测试源码 | frontend/src/、frontend/tests/ |
| 本机部署脚本与配置 | deploy/ |
| 文件长度与 SHA-256 清单 | MANIFEST.json |

## Windows 运行环境

- PowerShell 7.2 或更高版本。
- JDK 21 或更高版本，JAVA_HOME 指向 JDK 安装目录。
- Docker Desktop 的 Linux Engine 已启动；首次启动需要下载固定摘要的六个服务镜像。
- 运行已打包软件无需 Maven、Node.js；重新构建源码时需要 Maven、Node.js 22.13+（22.x）或 24+ 与 npm。
- 本机 5173、8080、15432、16379、19200、5672、18333 端口可用。

包内前端为正式静态构建文件，Nginx 提供页面并代理 API，不启动 Vite 开发服务器。PostgreSQL 保存业务记录，Redis 保存共享会话并执行限流，Elasticsearch 保存派生索引，RabbitMQ 驱动独立后台 Worker，SeaweedFS 提供私有 S3。基础 PDF 工具使用同步 HTTP 接口，Web 经 PdfExecutor 调用独立 Java PDF 子进程，受 90 秒期限和共享并发配额约束。

## 启动

解压 ZIP 后，在含有 backend、frontend、deploy 的 PDF_Extractor_PartB 目录打开 PowerShell。

~~~powershell
.\workbench.cmd start
.\workbench.cmd status
~~~

统一入口启动支撑服务、Web 和 Worker。重复启动复用当前受管实例。

浏览器访问 http://127.0.0.1:5173 。英文需求与接口原型位于 http://127.0.0.1:5173/part-a/submission/ 。本机初始 HTTP 服务只绑定回环地址，模型调用默认关闭。

首次启动自动创建 deploy/.env.local 和 deploy/runtime 中的本机凭据；重复启动保留原凭据和容器命名卷。已有同名 Docker Compose 实例时先使用其原部署目录停止服务，保留原目录的凭据与数据卷，不使用新生成的凭据连接旧数据卷。

## 检查

`workbench.cmd status` 显示受管进程和服务状态。浏览器入口可检查页面、PDF 操作与接口原型。

## 源码构建与测试

重新打包前执行 `workbench.cmd stop` 释放 JAR。完整测试需要本机支撑服务，在构建环境中执行：

~~~powershell
mvn.cmd -f backend/pom.xml test
mvn.cmd -f backend/pom.xml package
npm.cmd --prefix frontend ci
npm.cmd --prefix frontend test
npm.cmd --prefix frontend run build
~~~

完整 JUnit 测试使用本机支撑服务和独立随机测试模式；deploy/.env.local 由本部署实例生成。测试结果位于 backend/target/surefire-reports。浏览器测试需另安装 Playwright Chromium，并在 Web、Worker、Nginx 运行后执行：

~~~powershell
Push-Location frontend
npx.cmd playwright install chromium
Pop-Location
npm.cmd --prefix frontend run test:gateway
~~~

主报告所附执行记录对应 docs/part-b/evidence/manifest.json 的源码和证据摘要；本次源码测试的实际输出由上述命令生成。

## 停止与数据

~~~powershell
.\workbench.cmd stop
~~~

stop 保留容器命名卷和本机配置。重启使用 `workbench.cmd restart`。游客会话过期后，服务器聊天记录和上传文档暂不自动清理；账户数据同样保留。业务数据库与 S3 文件需配套备份。公网入口需另外配置域名、TLS、可信代理、防火墙及最小权限；该包的默认入口为 Windows 本机 HTTP 部署。
