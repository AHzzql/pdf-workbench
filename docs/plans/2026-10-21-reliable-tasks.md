# 可靠后台任务实施方案

## 目标

将索引、清理及阅读生成从 Web 内存执行迁移至 PostgreSQL 任务记录、事务 Outbox 和 RabbitMQ，部署独立 Windows Worker。原 11 个 PDF 同步接口不入队；任务消息只包含服务端任务 UUID 与投递版本。

## 数据与并发

Flyway V3 保存任务种类、空间、目标、状态、尝试次数、可执行时间、投递版本及递增 fencing token。业务记录、初始任务、Outbox 在同一数据库事务提交。提交使用数据库容量锁和分队列容量限制，每空间最多两个未完成生成，同会话最多一个；客户端请求标识在空间内保持幂等。领取使用数据库行锁，执行者续租并在有效 token 下写消息及终态。取消同时撤销执行权并提交 STOPPED；旧进程无法覆盖取消结果。过期任务由恢复器有限退避重试，失败终态与死信 Outbox 同事务写入。

## 交付与进程

Spring AMQP 3.2 随 Spring Boot BOM 管理。durable quorum 队列区分索引、生成和清理，初始并发分别为 2、2、1，预取 1，每类最多 128 个未完成项；队列长度受限且溢出拒绝发布。发布使用 mandatory、关联 confirms、持久化消息；确认和无退回均满足后标记 Outbox。领取 Outbox 使用 SKIP LOCKED 和短租约，网络等待不持有数据库事务。消费者在持久化后手动 ACK；重复/过期投递仅确认，不重复提交。数据库不可用时关闭消费通道并退避恢复，不作无限立即重入队。

Web 仅提交、查询与订阅快照，不连接 AMQP；Worker 负责 Outbox 发布、租约恢复与消费，不监听 HTTP。SSE 从共享持久快照读取，连接权限仍重读共享会话；跨实例取消通过数据库租约状态传播至 Worker 并中止本机模型请求。Worker 停机不将所有任务改为 STOPPED，执行权到期后由存活进程恢复。任务超时限制与心跳均独立于消费线程，取消目标为单任务虚拟线程，不中断共享 AMQP 消费线程。

## 接入顺序

1. TaskStore 与 V3：事务、容量、幂等、领取、fencing、重试、取消、Outbox。
2. Rabbit 拓扑与发布器：逐次确认、路由退回、持久化、容量限制、死信。
3. Worker 与业务处理器：独立进程，索引/清理/生成三类实际业务任务。
4. AiRuns/AiDocuments：共享任务与 SSE 快照，删除墓碑与后台清理。
5. 原生启动及验证脚本：Web/Worker、隔离测试命名空间与安全清理。
6. 真实 PostgreSQL/RabbitMQ/Redis/ES 集成测试：原子回滚、失去确认、重复投递、领取前后崩溃、过期接管、取消、有限重试及独立进程恢复；模型使用本机合成服务。

## 验证入口

- `mvn.cmd -f backend/pom.xml test`
- `.\deploy\Verify-Persistence.ps1`
- `.\deploy\Test-Local.ps1`
- `npm --prefix frontend run test:production`
- `npm --prefix frontend run test:gateway`
