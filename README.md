# PDF Workbench

基于 Vue 3 和 Spring Boot 的 PDF 文档处理平台，提供文档预览、内容提取、安全处理、数字签名和 AI 阅读功能。

## 功能

- PDF 连续预览、页码导航和结果定位
- 文本提取与正则搜索
- 表格识别与 CSV 导出
- 图片及原始资源导出
- PDF 加密与权限设置
- 数字签名、验签和签名移除
- 文字及图片水印
- 元数据、书签、批注和 XMP 信息读取
- 游客模式、账户会话和独立聊天记录
- AI 文档概览、问答、章节导航和历史会话

## 技术栈

| 层级 | 技术 |
|---|---|
| 前端 | Vue 3、TypeScript、Vite、PDF.js |
| 后端 | Java 21、Spring Boot、Apache PDFBox |
| 数据 | PostgreSQL、Redis、Elasticsearch |
| 异步任务 | RabbitMQ、独立 Worker |
| 文件存储 | SeaweedFS S3 |
| 网关 | Nginx |
| 部署 | Docker、Docker Compose |

## 目录

```text
backend/                 Spring Boot API、Worker、数据库迁移和测试
frontend/                Vue 应用和浏览器测试
Dockerfile               前后端多阶段构建
compose.yml              应用与中间件编排
nginx.conf               网关与静态资源配置
.env.example             环境变量模板
```

## Docker 部署

安装 Docker Engine 和 Docker Compose，复制环境变量模板，填写随机密码和有效的 `AI_API_KEY`：

```text
cp .env.example .env
docker compose up --build -d
```

应用入口：<http://127.0.0.1:5173>

API 文档：<http://127.0.0.1:5173/swagger-ui.html>

```text
docker compose ps
docker compose logs -f web worker
docker compose down
```

PostgreSQL、Redis、Elasticsearch、RabbitMQ、对象文件和 AI 解析数据保存在 Docker 命名卷中。删除全部本地数据：

```text
docker compose down -v
```

## Maven / IDEA 本地开发

复制本地配置模板，并使其中的密码与 `.env` 保持一致：

```text
cp backend/config/application-local.yml.example backend/config/application-local.yml
docker compose up -d postgres redis rabbitmq elasticsearch objects objects-init
```

使用 Maven 启动 API 和 Worker：

```text
mvn -f backend/pom.xml spring-boot:run -Dspring-boot.run.arguments=--tasks.role=all
```

IDEA 可直接运行 `cn.edu.pdfworkbench.PdfWorkbenchApplication`，工作目录设置为项目根目录，程序参数设置为 `--tasks.role=all`。

启动前端开发服务器：

```text
npm --prefix frontend ci
npm --prefix frontend run dev
```

开发入口：<http://127.0.0.1:5173>

## 测试

```text
mvn -f backend/pom.xml test
npm --prefix frontend test
npm --prefix frontend run test:gateway
```

浏览器测试连接已经启动的 Docker Compose 环境。

## 配置

| 变量 | 用途 |
|---|---|
| `POSTGRES_PASSWORD` | PostgreSQL 密码 |
| `REDIS_PASSWORD` | Redis 密码 |
| `ELASTIC_PASSWORD` | Elasticsearch 密码 |
| `RABBITMQ_PASSWORD` | RabbitMQ 密码 |
| `S3_ACCESS_KEY` | 对象存储访问密钥 |
| `S3_SECRET_KEY` | 对象存储私钥 |
| `AI_API_KEY` | AI 模型 API Key，Docker 完整部署必填 |
| `AI_BASE_URL` | OpenAI 兼容模型接口地址 |
| `AI_RERANK_URL` | 重排序接口地址 |

## 处理限制

- 上传 PDF 最大 50 MiB
- 普通 PDF 工具最多处理 300 页
- AI 阅读最多处理 200 页
- 扫描件不执行 OCR
