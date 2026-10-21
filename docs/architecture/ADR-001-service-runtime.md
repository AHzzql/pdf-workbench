# ADR-001：单机服务部署、检索与可靠后台任务

日期：2026-10-20  
状态：采用

## 背景与边界

系统首先在 Windows 本机部署，后续提供公网访问。前端为交互工作台，核心接口处理 PDF 文本、正则搜索、表格、图片、文档信息、加密、签名、水印和页面删除。现有 11 个核心接口保持 multipart 请求和同步响应契约。应用采用模块化单体，部署角色划分为 Web 进程、后台 Worker 和按任务创建的 PDF 子进程；不按业务操作拆分微服务。

## 拓扑与职责

```text
浏览器 ── Nginx ── Windows 原生 Spring Boot Web
           │                     ├── PostgreSQL：业务记录、任务状态、Outbox
           └── Vue 静态产物       ├── Redis：会话、限流、短期配额
                                 ├── Elasticsearch：可重建的文档检索索引
                                 └── 私有 S3：PDF、提取结果和导出文件
                                      ▲
PostgreSQL Outbox ── RabbitMQ ── Windows 原生 Worker
                                      └── 有界 Java PDF 子进程
```

前端采用 Vue 3、TypeScript、Vue Router、Pinia 和 PDF.js，保留 Vite 作为开发与构建工具。正式运行由 Nginx 提供构建后的静态资源并反向代理 API；不引入 SSR 或第二个业务后端。PostgreSQL 通过 Spring JDBC 和 Flyway 管理，私有对象存储采用 SeaweedFS 的 S3 接口。Redis、Elasticsearch、RabbitMQ 和 Nginx 使用 Linux 容器，应用与 PDF 子进程直接运行在 Windows JDK 上。Docker Desktop 承载容器运行环境，不承载项目开发环境。

## 决策一：Redis 的可过期状态

Redis 负责服务端会话、请求限流和临时配额，使用明确的命名空间与 TTL；共享会话采用 Spring Session Redis，不再并行保留 JDBC Session。业务记录、文件所有权、持久任务状态均由 PostgreSQL 保存。任务执行权以数据库租约及版本条件更新为准，不依赖 Redis 锁维持业务正确性。

Redis 配置 ACL、AOF、容量上限及 `noeviction`。会话不能作为普通热点缓存任意淘汰；达到容量上限时拒绝新增状态并返回可识别的服务错误。初始 AOF 策略为 `appendfsync always`，后续根据实际磁盘延迟调整。权限失效判断读取当前共享状态，不能长期使用单个 HTTP 请求捕获的会话对象副本。[Spring Session 配置](https://docs.spring.io/spring-session/reference/configuration/redis.html)、[Redis 持久化](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/)。

## 决策二：Elasticsearch 的检索边界

Elasticsearch 保存从 PDF 派生的文本块、页码、文档标识、空间标识、版本和向量，统一承担全文及向量召回。采用共享索引与版本化别名，不为每个文档或空间建立一个索引。所有查询在服务端追加空间和文档范围过滤；按标识读取、下载与删除仍查询业务库的所有权及删除状态。索引不能作为授权依据或文件主存储。

初始索引采用单主分片、零副本；单机无副本故障转移能力。中文采用内置 CJK 分析器，英文保留词项分析；与原 SmartChinese 分词的召回差异通过固定中英文样本比较。向量字段维度与嵌入输出固定一致，BM25 与向量结果在 Java 中按现有 RRF 排序规则合并。迁移后删除嵌入式 Lucene 的双份索引写入。已有正则搜索仍按原文执行并返回精确偏移，不转换成全文匹配查询。[向量字段与近邻索引](https://www.elastic.co/docs/reference/elasticsearch/mapping-reference/dense-vector)。

写索引采用批量接口及稳定文档标识；只有目标版本全部写入后才切换就绪状态。删除先在 PostgreSQL 写入墓碑，再异步清理对象与索引，防止旧任务重新写入被删除的数据。索引可以从业务记录和对象存储重建。检索服务故障返回检索不可用，不将故障解释成零条结果；原有不依赖索引的 PDF 操作继续执行。

索引构建在 PostgreSQL 保存独立 UUID 令牌、10 分钟租约与就绪代次，每批嵌入前后续约。所有批次通过 ES 逐项状态检查、刷新及数量校验后，以令牌和有效租约条件更新就绪指针；查询同时过滤空间、文档、就绪代次、索引版本和允许的文本块。重建失败保留原就绪代次，不暴露半成品。删除事务锁定文档行、检查会话引用并写入墓碑，创建引用采用相同行锁；已撤销或过期的构建无法发布就绪状态。派生旧代次和进程崩溃残留统一进入生命周期清理范围。[批量写入](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-bulk)、[kNN 过滤](https://www.elastic.co/docs/reference/query-languages/query-dsl/query-dsl-knn-query)、[索引别名](https://www.elastic.co/docs/manage-data/data-store/aliases)。

## 决策三：RabbitMQ 的可靠任务投递

RabbitMQ 负责索引构建、重建、文件清理及其他可异步执行的耗时任务。现有同步 PDF 接口由本机有界协调器直接启动受限子进程，不为保持同步响应而先入队再阻塞等待。

业务事务同时写入任务和 Outbox，发布器使用 `FOR UPDATE SKIP LOCKED` 分批领取事件，启用持久化消息、publisher confirms 和 mandatory 路由校验；确认成功后更新 Outbox。确认丢失时允许重发，消费端以任务标识、版本和数据库条件更新实现幂等。交付语义为至少一次，消费端承担重复处理控制。

Worker 完成目标版本的持久化后手动 ACK，重复的终态任务直接确认。解析与索引使用独立队列和并发额度，消费预取初始为每消费者 1。任务领取带数据库租约和 fencing token，旧执行者无法提交新租约下的结果。可重试失败采用有限次退避投递，重试次数和下次执行时间保存到任务记录；永久失败进入死信队列。队列及 Outbox 都有容量限制，不使用无限重入队。

采用 durable quorum queue，本机为单节点；单节点不能抵御主机或磁盘整体故障。文件内容、打开密码、证书私钥不进入消息，消息仅包含任务标识和必要的版本信息。[RabbitMQ 可靠交付](https://www.rabbitmq.com/docs/reliability)、[发布确认与消费确认](https://www.rabbitmq.com/docs/confirms)。

## 决策四：Nginx 同源入口与 Windows 网络

Nginx 提供同源静态资源、请求体限制和反向代理。构建资源按内容哈希长期缓存，API 和私有下载不缓存；上传请求流式转发，流式响应关闭代理缓冲。业务参数不进入 URL，日志不记录正文、授权头、会话 Cookie 或下载凭据。应用限流读取经过明确代理边界校验的客户端地址；外部提供的转发头不能直接作为可信身份。

本机入口绑定 `127.0.0.1:5173`，Web 绑定 `127.0.0.1:8080`，容器通过 `host.docker.internal` 访问原生应用。5173 的 Nginx 模式与 Vite 开发模式互斥。部署脚本应验证容器到宿主应用的连通性，不依赖宿主 LAN 地址或写死容器 IP。Docker Desktop 提供宿主专用 DNS；Windows 原生 Nginx 的并行处理存在平台限制，因此入口采用 Linux Nginx 容器。[Docker 宿主网络访问](https://docs.docker.com/desktop/features/networking/networking-how-tos/)、[Nginx Windows 平台限制](https://nginx.org/en/docs/windows.html)、[Nginx 代理缓冲](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_buffering)。

公网发布使用独立入口配置：TLS、正式域名允许列表、安全 Cookie、可信代理范围、必要的防火墙规则；数据库、Redis、Elasticsearch、AMQP 和对象存储管理端口不发布到公网。本机 HTTP 配置不复用为公网 TLS 配置。

## 容量、故障与运维

本机容器初始上限为 Nginx 128 MiB、PostgreSQL 512 MiB、Redis 256 MiB、Elasticsearch 2 GiB、RabbitMQ 512 MiB。Redis 内部数据上限 160 MiB，为 AOF 重写和连接开销预留空间。容量基线以实际压力测试确定。PDF 子进程初始并发为 2，每任务限制堆、运行时间、页数、输出大小和工作目录；密码通过进程管道传递，不放入命令行。异步线程只承担调度及 I/O，不能隔离解析器的内存耗尽或进程崩溃。

数据库和对象存储执行配套备份；Elasticsearch 从源数据重建，Redis 丢失后的临时状态重新建立。容器命名卷不替代备份。单机部署有主机级单点故障；多机容灾通过后续独立部署设计处理。仅保留应用结构化日志、组件自带健康检查和故障测试，不部署 Prometheus、Grafana、ELK 日志采集、Kubernetes 或独立配置中心。

## 取舍

- PostgreSQL 单独完成全文检索和数据库任务队列可以减少部署数量，但无法提供这里选定的独立检索与任务投递边界。
- Redis Streams 与 RabbitMQ 同时承担任务总线会形成双队列和重复恢复机制；任务总线统一为 RabbitMQ。
- Elasticsearch 增加内存、磁盘和索引一致性成本，因此仅保存派生检索数据，业务事务留在 PostgreSQL。
- Java 多线程适合并发调度，独立 PDF 子进程负责任务级故障边界；两者职责不同。

## 验证项目

1. 同一套浏览器用例分别经过原生 Web 静态入口和 Nginx 入口运行，校验上传、下载、会话和流式响应。
2. 两个 Web 实例读取相同共享状态；重启后验证有效状态保留、过期和撤销状态不可恢复。
3. 数据库迁移可在空库执行并支持重复启动；所有查询、更新、删除按空间隔离。
4. 发布确认丢失、重复投递、消费前后崩溃、租约超时均不能重复提交任务结果。
5. 检索停机、重建、删除与旧版本写入竞争不影响业务所有权判断；中英文检索对照固定样本结果。
6. PDF 子进程超时、异常退出及输出超限后能够释放额度并清理工作目录。
