# 项目交付清单

## GitHub 源码与部署交付

| 类别 | 文件 | 说明 |
| --- | --- | --- |
| 后端源码与构建 | `backend/src/`、`backend/pom.xml` | Java 21、Spring Boot、JUnit 测试及 Flyway 迁移 |
| 前端源码与构建 | `frontend/src/`、`frontend/tests/`、`frontend/package*.json` | Vue / TypeScript、Vitest、Playwright |
| 容器镜像构建 | `Dockerfile`、`.dockerignore` | Maven、npm 多阶段构建 |
| 一键运行 | `compose.yml`、`nginx.conf` | 网关、Web、Worker 与基础设施 |
| 配置样例 | `.env.example` | 需要自行填充凭据；实际 `.env` 不上传 |
| 部署与测试指导 | `README.md`、`DELIVERY.md` | 标准 Docker、Maven、npm 命令 |

`mvn -f backend/pom.xml package` 的产物是 `backend/target/pdf-workbench-1.1.0.jar`，前端构建产物为 `frontend/dist/`。二者均可由仓库源码重新生成；Docker Compose 通过 Dockerfile 自动完成构建。GitHub 源码仓库不提交 `target/`、`dist/`、依赖缓存、运行数据库或 PowerShell 管理脚本。

## 课程报告与演示文件

课程报告保存在本地项目目录 `docs/`，由课程提交要求单独管理，**当前没有随公开 GitHub 源码同步**：

| 文档 | 本地路径 |
| --- | --- |
| Part A 英文需求与设计 | `docs/part-a/submission/PartA_Submission.docx` |
| Part B 中文设计与测试 | `docs/part-b/PartB_Submission.docx` |
| 课堂演示 PPT | `docs/part-b/presentation/PartB_Demonstration.pptx` |
| 历史测试证据 | `docs/part-b/evidence/` |

这些材料是独立的课程交付对象。历史文档仍包含 Windows PowerShell 部署说明和较早测试结果，在正式课程提交前需使用当前 Docker Compose 版本的实际执行证据进行人工复核。历史报告不应作为新 Docker 版本已通过验收的证明。

## 部署验证标准

1. `docker compose config -q` 通过，且 `.env` 仅含本地凭据。
2. `docker compose up --build -d` 能从源码构建并启动。
3. `docker compose ps` 的长运行服务正常；`objects-init` 初始化任务可以正常退出。
4. 网关 `http://127.0.0.1:5173` 返回前端页面，`/v3/api-docs` 返回接口文档。
5. 使用无敏感内容的测试 PDF 检查文本、表格、图片、加密、签名与水印的主要操作。
6. 后端 JUnit、前端测试和浏览器端到端测试的结果分别记录；不能把旧版本测试结果当作当前版本证据。
7. `docker compose down` 后命名卷仍存在；常规停止不执行 `down -v`。

## 运行方式

推荐使用根目录 `docker compose up --build -d` 运行完整项目。IDEA 可运行 Java Web、Worker，Maven 可构建和测试，npm 可运行前端开发服务；本地开发时需要额外正确配置依赖服务地址和环境变量。详细命令见 [README.md](README.md)。

## 不纳入公开交付的文件

- `.env`、`.env.local`、实际数据库密码与访问令牌。
- 私钥、签名证书容器、用户文件、会话与运行数据。
- IDE 缓存、`node_modules/`、`target/`、`dist/`、临时日志与旧部署脚本。
- 为本机验证生成的临时目录和历史备份。
