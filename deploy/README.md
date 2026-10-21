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

`Start-Local.ps1` 创建本机凭据并启动六个容器，初始化私有存储桶；重复调用保留已有凭据和命名卷。`Start-Web.ps1` 启动 Windows 原生 Java 服务，默认关闭模型调用；数据目录为 `deploy/runtime/data`。模型配置通过环境变量或本机配置文件提供，显式使用 `-EnableModel` 启用。

在第二个 PowerShell 会话启动 Worker：

```powershell
.\deploy\Start-Worker.ps1
```

Web 仅提交与读取持久任务，不连接 AMQP；Worker 统一发布 Outbox、维护租约并消费任务，不监听 HTTP，二者使用相同数据库、任务命名空间与数据目录。模型调用默认关闭；启用时 Web 和 Worker 均使用 `-EnableModel`。更改 `-DataDirectory` 或 `-DatabaseSchema` 时两进程保持一致。

访问 `http://127.0.0.1:5173`。Nginx 与 Vite 开发服务器共用 5173，不能同时监听。8080 为原生 Web 回环端口；15432、16379、19200、5672、18333 分别为本机 PostgreSQL、Redis、Elasticsearch、AMQP 和 S3 端口。

`http://127.0.0.1:5173/part-a/` 提供需求文档、图形和本地 Swagger 原型。原型通过相对根地址调用同源 API，不额外开放跨域来源；缺失文件返回 404，隐藏文件与 Word 临时锁文件禁止访问。

## 验证

在另一个 PowerShell 会话执行：

```powershell
.\deploy\Test-Local.ps1
.\deploy\Test-Initialization.ps1
.\deploy\Test-ObjectStore.ps1 -Restart
.\deploy\Verify-Persistence.ps1
npm --prefix frontend run test:gateway
```

`Verify-Persistence.ps1` 使用 8180、8181 两个 Web、独立 Worker、8189 本机模型协议夹具及合成 PDF。验证包括共享会话、账户及文档隔离、跨节点撤销、强制终止后恢复、分布式限流、基础 PDF 故障边界；还会让 Web 无法连接 AMQP，终止提交进程后由 Worker 发布并完成任务，随后强制终止生成中的 Worker，校验租约接管、消息不重复及新 fencing token。取消由另一 Web 提交，并验证 SSE、模型连接关闭及消费者继续可用；文档通过队列完成墓碑后的清理。

模型请求仅连接 8189 的合成夹具。验证目录位于 `backend/target`，结束时删除随机数据库模式、ES 索引与 RabbitMQ 队列；8180、8181 或 8189 被占用时停止验证，不终止其他进程。执行前运行 Maven 测试并打包，以提供合成 PDF 与测试夹具。

Maven 集成测试使用本机真实 PostgreSQL、Redis、Elasticsearch、RabbitMQ 和私有 S3；每组数据测试使用随机模式、索引、队列命名空间及对象 UUID。普通 PDF 工具测试及独立示例保持原调用方式。

## 私有对象存储

SeaweedFS 4.48 使用独立 bridge 网络，关闭容器间通信，仅将 S3 HTTP 端口发布到 `127.0.0.1:18333`。master、volume、filer 的 HTTP/gRPC 监听容器回环地址；S3 gRPC 不发布，IAM 写入缓存使用内部 JWT。IAM HTTP、Iceberg、Lance 和自动建桶关闭。应用身份只有 `pdf-workbench-private` 桶的 Read/Write/List 权限；桶初始化通过本机容器管理入口执行。

`Initialize-Local.ps1` 追加生成 S3 与内部 JWT 凭据，保留原有服务密码；身份和安全配置写入忽略的 runtime 文件，已有配置不一致时中止，不覆盖。`Initialize-ObjectBucket.ps1` 校验桶所有者并设置每次增长一个 1 GiB 卷、写入 fsync；单实例最多 8 个卷，容器内存上限 512 MiB。独立网络并非断网环境；Docker Desktop 的 internal 网络不提供这里所需的宿主端口映射。

`Test-ObjectStore.ps1` 从打包程序运行探针，验证随机对象的跨客户端读取、SHA-256、条件写入、匿名/错误密钥/跨桶/策略写入拒绝和幂等删除；`-Restart` 在写入后重启对象存储容器，再由新 JVM 读取，验证命名卷保留。脚本只重启 objects 服务，临时清单位于 `backend/target/object-probe-*`；若删除探针失败，保留清单供重试，不扫描或删除业务对象。S3 是应用内部服务，不通过 Nginx 为浏览器代理或提供公开文件 URL。

## 后台任务

`TASK_NAMESPACE` 默认为 `pdf-workbench.tasks.v1`，`TASK_ROLE` 区分 `web`、`worker`；`all` 用于同进程集成测试。`RABBITMQ_HOST`、`RABBITMQ_PORT`、`RABBITMQ_USER`、`RABBITMQ_PASSWORD`、`RABBITMQ_VHOST` 配置 AMQP，密码由启动脚本载入。索引、生成、清理队列各有容量限制，预取 1；原 11 个 PDF 同步接口不入队。

业务写入、任务和 Outbox 同事务提交。发布使用持久化消息、mandatory 与关联确认；业务终态提交后手动 ACK，重复消息按任务版本和 fencing token 去重。领取后崩溃的任务在租约到期后重试；领取前未处理的已确认投递有定期重发。运行任务初始租约 30 秒、执行窗口 15 分钟、最多三次尝试。取消撤销数据库执行权，并中断单任务线程；Web 或 Worker 重启不批量改写旧消息状态。升级前没有持久任务记录的消息不会自动恢复。

停止进程保留数据库、Redis、ES 和队列数据。执行终态及已发布 Outbox 的保留期、游客过期回收与旧索引代次自动回收尚未接入调度。

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
