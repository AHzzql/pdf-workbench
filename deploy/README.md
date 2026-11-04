# 本机部署

根目录 `workbench.cmd` 是统一运行入口：

```text
workbench.cmd start
workbench.cmd status
workbench.cmd restart
workbench.cmd stop
```

启动前先构建前端和后端：

```text
npm.cmd --prefix frontend ci
npm.cmd --prefix frontend run build
mvn.cmd -f backend/pom.xml -DskipTests package
```

运行需要 JDK 21、PowerShell 7.2 和 Docker Desktop。Java Web 与 Worker在本机运行；PostgreSQL、Redis、RabbitMQ、Elasticsearch、对象存储和 Nginx 网关由 `compose.yml` 管理。入口为 `http://127.0.0.1:5173`。

部署流程包括本地凭据初始化、中间件启动、Web/Worker 进程运行和状态记录。
