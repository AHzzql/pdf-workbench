# 本机部署

项目对外只提供根目录 `workbench.cmd`：

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

其余 PowerShell 文件是该入口的内部实现，分别负责初始化本地凭据、启动中间件、运行 Web/Worker 和记录进程状态，不作为独立操作命令。
