# Windows 本机部署

## 运行环境

- Windows、PowerShell 7.2 或更高版本。
- `JAVA_HOME` 指向 JDK 21 或更高版本，Maven 可用。
- Node.js 与 npm 可用，Docker Desktop 的 Linux Engine 已启动。
- 数据库、缓存、检索及消息队列端口均绑定回环地址。

## 构建与启动

在项目根目录执行：

```powershell
npm --prefix frontend ci
npm --prefix frontend run build
.\deploy\Start-Local.ps1
mvn.cmd -f backend/pom.xml package
.\deploy\Start-Web.ps1
```

`Start-Local.ps1` 创建本机凭据并启动五个容器；重复调用保留已有凭据和命名卷。`Start-Web.ps1` 启动 Windows 原生 Java 服务，默认关闭模型调用；数据目录为 `deploy/runtime/data`。模型配置通过环境变量或本机配置文件提供，显式使用 `-EnableModel` 启用。

访问 `http://127.0.0.1:5173`。Nginx 与 Vite 开发服务器共用 5173，不能同时监听。8080 为原生 Web 回环端口；15432、16379、19200、5672 分别为本机 PostgreSQL、Redis、Elasticsearch 和 AMQP 端口。

## 验证

在另一个 PowerShell 会话执行：

```powershell
.\deploy\Test-Local.ps1
.\deploy\Verify-Persistence.ps1
npm --prefix frontend run test:gateway
```

`Verify-Persistence.ps1` 使用 8180、8181 两个独立 Java 进程、独立数据库模式及合成 PDF，验证共享数据、权限隔离、跨节点撤销、强制终止后的恢复和分布式限流；再将独立节点的 Redis 和 ES 地址指向本机关闭端口，验证会话返回 503、携带原会话 Cookie 的 PDF 基础操作仍成功。模型地址限定为本机关闭的测试端口；验证数据位于 `backend/target`，数据库模式在结束时删除。8180、8181 被占用时停止验证，不终止其他进程。

Maven 集成测试连接本机真实 PostgreSQL、Redis 和 Elasticsearch，为每组数据测试创建随机数据库模式及测试索引，外部模型使用合成向量；普通 PDF 工具测试及独立示例保持原调用方式。

## 文档索引

检索使用共享别名 `pdf-workbench-chunks`，初始物理索引为 `pdf-workbench-chunks-v1`，采用单主分片、零副本、内置 CJK 分析器及 1024 维余弦向量。修改嵌入模型后按数据库索引版本重建，原有 PDF 正则接口不经过 ES。`ELASTIC_URL`、`ELASTIC_USER`、`ELASTIC_PASSWORD` 和 `ELASTIC_ALIAS` 提供连接配置；`Start-Web.ps1` 从本机凭据文件载入密码。

每个构建有独立代次；全部批次写入、刷新并计数成功后，数据库条件更新发布就绪指针。构建中断或删除不能发布半成品。索引读取失败返回 503，不按空结果处理；基础 PDF 接口独立于检索服务。旧的本机 Lucene 索引不再读写，既有目录在文档删除时清理。旧 ES 代次及异常退出残留的自动回收尚未接入调度。


## 旧数据导入

停止使用旧目录的应用后执行：

```powershell
.\deploy\Import-Legacy.ps1 -AccountSource 'D:\backup\accounts' -ReadingSource 'D:\backup\reading' -FileTarget 'D:\workbench-data'
.\deploy\Start-Web.ps1 -DataDirectory 'D:\workbench-data'
```

导入保留标识、编码后的密码及文件原件，重复导入不覆盖 PostgreSQL 中已存在的新数据。原共享游客目录导入隔离空间，不分配给新的会话。源数据库以只读方式打开；目标文件冲突或缺失时中止数据库事务。数据库回滚不删除已经复制的文件，目标目录可能保留无业务引用的文件。

## 数据与安全边界

`deploy/.env.local` 与 `deploy/runtime` 不进入 Git；更换凭据需要同步实际服务中的认证配置，不直接覆盖旧凭据文件。容器命名卷保存服务数据，删除容器不等于删除命名卷。业务库与文件目录需要配套备份。公网入口采用独立 TLS、域名、可信代理和防火墙配置；本机 HTTP 配置只监听回环地址。
