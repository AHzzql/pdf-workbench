# PDF Extractor / PDF Workbench

基于 Spring Boot 3、Java 21、Vue 3 与 PDFBox 的 PDF 文档处理工作台。提供分页文本与属性提取、正则搜索、规则表格与 CSV 导出、图片资源导出、文档信息、AES 加密、数字签名、签名验证与移除，以及文字/图片水印。可选账户及 AI 阅读功能属于扩展模块。

## 技术栈

- 后端：Java 21、Spring Boot、Apache PDFBox、Maven、JUnit。
- 前端：Vue 3、TypeScript、Vite、PDF.js、Vitest、Playwright。
- 基础设施：Nginx、PostgreSQL、Redis、RabbitMQ、Elasticsearch、SeaweedFS。
- 部署：Docker Engine + Docker Compose。Web 和 Worker 使用同一后端镜像，通过 `TASK_ROLE` 与 Web 应用模式区分职责。

## 仓库结构

| 路径 | 内容 |
| --- | --- |
| `backend/pom.xml`、`backend/src/` | Java 工程、数据库迁移和 JUnit 测试 |
| `frontend/package.json`、`frontend/src/`、`frontend/tests/` | 前端工程和测试 |
| `Dockerfile` | 前后端多阶段镜像构建 |
| `compose.yml` | 网关、Web、Worker 和依赖服务编排 |
| `nginx.conf` | 静态站点与同源 API 代理 |
| `.env.example` | 不含实际凭据的部署变量示例 |
| `DELIVERY.md` | 交付范围、运行验收与限制 |

## 运行整个系统：Docker Compose

前置条件：已安装并启动 Docker Desktop（Linux containers）或 Docker Engine，支持 Docker Compose；本机端口 `5173` 未被占用。首次部署需要下载构建镜像和服务镜像，并预留充足磁盘和内存。

**Windows PowerShell**

```powershell
Copy-Item .env.example .env
# 编辑 .env，为每个密码和 S3 密钥设置独立随机值；不要提交 .env
docker compose config -q
docker compose up --build -d
docker compose ps
```

**Linux / macOS**

```bash
cp .env.example .env
# 编辑 .env 中的凭据
docker compose config -q
docker compose up --build -d
docker compose ps
```

默认入口：http://127.0.0.1:5173

- Swagger UI：http://127.0.0.1:5173/swagger-ui.html
- OpenAPI 文档：http://127.0.0.1:5173/v3/api-docs
- 查看日志：`docker compose logs -f web worker gateway`
- 重新构建：`docker compose up --build -d`
- 停止并移除容器：`docker compose down`（保留命名卷）
- **彻底清空本项目数据**：`docker compose down -v`（不可逆，不用于常规停止）

Compose 会自动构建前端静态资源和后端 JAR，无需手工执行 Maven、npm 或 PowerShell 启动脚本。PostgreSQL、Redis、队列、检索索引、对象存储及 AI 数据使用命名卷持久化；默认仅将 Nginx 网关发布到本机回环地址。

## 使用 IDEA / Maven 开发与测试

在 IntelliJ IDEA 中打开 `backend/pom.xml`，使用 JDK 21；前端使用 `frontend/package.json`，建议 Node.js 22。直接运行 `PdfWorkbenchApplication.main()` 启动的是 Web 应用，需要能够访问相应基础设施并提供数据库、Redis、RabbitMQ、对象存储等环境变量；仅启动 Java 进程不能自动创建依赖。

不使用 Compose 时可独立编译：

```bash
mvn -f backend/pom.xml -DskipTests package
npm --prefix frontend ci
npm --prefix frontend run build
```

测试命令：

```bash
mvn -f backend/pom.xml test
npm --prefix frontend test
npm --prefix frontend run typecheck
npm --prefix frontend run lint
```

部分后端集成测试依赖实际启动的中间件；浏览器端到端测试需要运行中的工作台和 Playwright Chromium。独立 Worker 的 `TASK_ROLE=worker` 及 `SPRING_MAIN_WEB_APPLICATION_TYPE=none` 由 Compose 负责配置。

## 限制与数据安全

- PDF 上传最大 50 MiB；普通工具最多处理 300 页，AI 阅读最多 200 页。
- 单个 Web 实例默认有 2 个同步 PDF 处理槽，满载请求返回 HTTP 429；不是整个站点最多只能有 2 个用户。
- 正则检索操作的是当前 PDF 的文本，不依赖 Elasticsearch。同步 PDF 工具不经过 RabbitMQ。
- 默认绑定 `127.0.0.1`，仅供本机运行；公网部署还需 HTTPS、可信代理和安全配置。
- 不提交 `.env`、证书私钥、数据库文件、用户 PDF、运行日志及构建缓存。

项目交付明细见 [DELIVERY.md](DELIVERY.md)。
