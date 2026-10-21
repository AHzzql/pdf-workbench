# 私有对象存储实施方案

## 目标与边界

将持久 PDF 和派生内容迁移到私有 S3，消除 Web 与 Worker 对同一文件目录的依赖。PostgreSQL 保存所有权、对象清单、大小、SHA-256 和发布状态；S3 不能作为授权依据。原 11 个同步 PDF 接口继续使用请求级临时目录，不以 S3 的可用性作为处理前提。

## 技术选择

采用 SeaweedFS 4.48 的 S3 接口与 AWS SDK for Java 2.56.0 同步客户端，传输使用 URLConnection，避免引入第二套异步网络栈。SeaweedFS 采用单容器 master、volume、filer、S3，命名卷保存元数据与文件块；只发布回环 S3 端口，独立容器网络不连接其他服务。关闭 IAM API、Iceberg、Lance 和自动建桶，配置内部 JWT、静态身份和桶级 Read/Write/List 权限，无匿名身份、应用 Admin 权限或公网管理端口。桶由本机容器管理入口初始化，不向应用分发管理密钥。

本机目录方案需要共享盘和跨进程目录约束，无法实现所选存储边界；直接把文件保存到 PostgreSQL 会扩大业务备份和事务负担。S3 增加了网络故障及跨资源一致性成本，因此采用分阶段发布，不在数据库行锁中等待上传，也不把对象存储异常当作文件不存在。

## 一、部署及传输边界

文件：`deploy/compose.yml`、`deploy/Initialize-Local.ps1`、`deploy/Start-Local.ps1`、`deploy/Initialize-ObjectBucket.ps1`、`deploy/Test-ObjectStore.ps1`、`backend/pom.xml`、`storage/S3Settings`、`storage/S3ObjectStore`、`storage/ObjectStoreProbe`。

1. 固定镜像 digest；在现有初始化逻辑中追加对象存储凭据，不重置 PostgreSQL、Redis、ES 或 RabbitMQ 密码。配置只写入忽略的 runtime 目录，重复启动保持密钥。
2. 初始化私有桶并设置小规模卷增长参数；已有桶不覆盖。健康检查验证 S3 拒绝匿名访问，不仅检查进程存活。
3. 对象键只由服务端 UUID 和固定文件种类生成，不接收路径或原始文件名。传输限定 PDF 50 MiB、内容 JSON 32 MiB，写入使用校验和及禁止覆盖条件，读取按可信清单校验长度与 SHA-256。
4. 限制连接、单次请求、重试及响应体读取时间；失败中止响应流并清理部分临时文件，不覆盖已存在的目标文件。SDK 异常不直接传递到 API、日志或终端。
5. 真实 S3 验证读写、跨客户端读取、条件写入、匿名拒绝、错误密钥拒绝、跨桶和桶管理拒绝、幂等删除及重启持久化；协议夹具覆盖损坏、截断、过量响应及超时。

## 二、清单与业务切换

文件：Flyway V4、`storage/DocumentStorage`、`ai/AiRepository`、`ai/AiDocuments`、`ai/AiController`、`ai/AiTaskHandler`、Worker 维护入口及对应测试。

1. 先在 PostgreSQL 保存带租约的暂存清单，再上传不可变对象；PDF 与内容校验完成后，同事务发布文档和可读清单。网络请求不持有数据库事务。数据库提交结果不确定时保留暂存记录，不直接删除可能已发布对象。
2. 下载先验证空间、文档和墓碑，再读取对象；临时副本只作传输缓存。Web 与 Worker 使用不同临时目录仍能处理同一文档。
3. 删除先写墓碑并提交 CLEAN 任务；Worker 幂等删除清单中的精确对象，成功后删除业务记录。中途故障重试不能恢复已删除文档或误删其他空间。
4. 过期暂存清单由 Worker 维护回收，已发布对象不参与暂存回收；覆盖上传后崩溃、提交前后崩溃和清理重试竞争。

## 三、迁移与回归

提供显式、可重复的本机文件迁移入口；校验源 PDF 与文档哈希，保留原件，清单提交前不切换读取。旧 H2 导入与 S3 迁移使用同一数据完整性规则。S3 故障不能触发静默本机回退。

运行后端完整测试、原生双 Web/Worker 恢复测试、Nginx 探针、前端检查及浏览器回归。新增验证包括不同数据目录、跨空间下载/删除拒绝、S3 停机与恢复、源文件损坏、重复迁移、暂存回收以及无引用对象处理。开发状态与实际执行结果记录在 `DEV_STATE.md`，正式 Part A 业务范围保持不变。

## 依据

- [SeaweedFS 4.48](https://github.com/seaweedfs/seaweedfs/releases/tag/4.48)
- [S3 凭据与桶级权限](https://github.com/seaweedfs/seaweedfs/wiki/S3-Credentials)
- [内部 JWT 配置](https://github.com/seaweedfs/seaweedfs/blob/4.48/weed/command/scaffold/security.toml)
- [S3 IAM gRPC 安全边界](https://github.com/seaweedfs/seaweedfs/security/advisories/GHSA-5fx4-c9qp-36jc)
- [SDK URLConnection 生命周期](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/http-configuration-url.html)
