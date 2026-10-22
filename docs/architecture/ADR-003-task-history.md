# ADR-003：任务历史保留与死信摘要归档

日期：2026-10-22。状态：采用。

## 背景

PostgreSQL 保存任务及 Outbox；RabbitMQ 的 reading、index、cleanup、dead 均为有容量上限的 quorum 队列。无人消费的 dead 队列填满后会拒绝新发布，未确认的 Outbox 持续占用投递容量。任务历史不应与聊天记录共用保留周期。

## 决策

Worker 增加独立 dead 消费者，prefetch 为 1。消息先写入 PostgreSQL `task_dead_letters`，提交后手动 ACK；数据库失败退避 5 秒再显式 NACK/requeue，不执行业务处理器。退出时中断退避仍归还消息；通道故障关闭通道，由 broker 重投递。RabbitMQ 4.4 的显式 NACK 不增加失败投递计数；实际通道/连接故障仍受 quorum delivery-limit 约束。

摘要表以任务命名空间和消息 SHA-256 为联合主键，记录字节数、首次/最近接收时间。仅当 JSON 不超过 256 字节、恰好包含 taskId/version、UUID 有效且版本为正整数时保存任务标识。原始消息、扩展字段、头信息和异常正文不落库。重复投递只更新最近接收时间，不增加行数，不恢复失败任务；CLEAN 的补偿仍由独立业务规则决定。摘要不设任务外键，任务历史删除不级联移除仍在保留期内的故障记录。

保留原队列参数，不通过删除/重建队列升级，也不以队列 TTL 直接丢弃尚未归档的消息。归档使用已有 PostgreSQL，不增加管理控制台或监控中间件。

## 保留规则

| 配置项 | 初始值 | 条件 |
| --- | --- | --- |
| tasks.retention.published-outbox-days | 7 天 | 已确认发布、无活动发布租约，且不是活动任务当前投递版本 |
| tasks.retention.terminal-task-days | 30 天 | 任务终态、无任何 Outbox、不关联 RUNNING 消息，且不是仍有 DELETING 文档的 CLEAN |
| tasks.retention.dead-letter-days | 30 天 | 当前命名空间最近接收时间超过保留期 |

范围均为 1–3650 天。TaskMaintenance 独立线程每 60 秒逐类处理最多 128 条，使用排序候选和 `FOR UPDATE SKIP LOCKED`，不扫描删除活动对象，不持有网络调用。过期事件先删除，再清理已无事件的终态任务；待发布事件不因任务年龄过期。多个 Worker 可并发清理，跳过其他事务持有的行锁。吞吐超过每类每分钟 128 条时，过期积压需要后续轮次处理，保留期不是精确删除时刻。

聊天消息、账户和持久文档不随任务历史删除。请求幂等结果仍取自消息记录；SSE 在调度记录已移除时回退到消息状态。失败 CLEAN 的墓碑保护优先于任务年龄，避免保留期清理切断删除补偿。

同一业务数据库模式的 Web/Worker 使用相同任务命名空间；修改命名空间后，旧命名空间摘要需由旧配置实例执行保留期维护。数据库的正常行删除释放可复用空间，文件体积回收取决于 PostgreSQL vacuum，不在应用请求内执行 VACUUM FULL。

## 验证

PostgreSQL 测试覆盖过期/近期/待发布事件、活动版本和发布租约保护、消息/文档保护、三类 128 条批量边界、并发删除和命名空间隔离。API 测试验证任务删除后的消息、请求幂等及 SSE。RabbitMQ 测试以实际 quorum 队列连续显式归还 32 次，再模拟数据库已提交而 ACK 丢失，由 Worker 归档重投递，仅保留一条摘要。原生回归通过实际 S3 故障产生终态死信，由独立 Worker 提交摘要并完成后续清理补偿。

依据：[消费者确认与通道关闭](https://www.rabbitmq.com/docs/confirms)、[Quorum 消息归还与计数](https://www.rabbitmq.com/docs/quorum-queues#poison-message-handling)、[死信配置与参数优先级](https://www.rabbitmq.com/docs/dlx)。
