# 任务历史与死信保留设计及实施计划

## 目标

限制后台调度历史的长期增长，释放死信队列容量，不改变账户、会话、聊天消息及持久文档的保留规则。

## 设计

单纯延长死信队列或丢弃旧消息不能提供持久故障记录；另建消息存储服务会增加运维组件。沿用 PostgreSQL，Worker 增加单个死信消费者，在数据库提交摘要后手动 ACK。归档仅包含命名空间、消息 SHA-256、字节数、有效的任务 UUID/投递版本、首次与最近接收时间，不保存原始消息正文、请求头或异常文本。无效消息只保留摘要及长度，同一命名空间/摘要幂等合并；归档不执行或重启业务任务。

数据库不可用时死信保持未确认，短暂退避后显式 NACK/requeue；RabbitMQ 4.4 的显式 NACK 不增加 delivery-count，避免反复关闭通道耗尽默认投递次数。连接中断仍由 broker 重投递。既有队列参数不变，不删除或重建业务队列。

初始保留期通过 `tasks.retention.published-outbox-days=7`、`terminal-task-days=30`、`dead-letter-days=30` 配置，范围 1–3650 天。TaskMaintenance 的独立线程每 60 秒分别最多删除 128 条过期已发布事件、终态任务和死信摘要；不占用心跳线程。保留待发布/有活动租约的 Outbox、活动任务当前版本的事件、仍有任何 Outbox 的任务、仍关联 RUNNING 消息的任务、仍有 DELETING 文档的 CLEAN。聊天消息保留请求幂等结果，SSE 在任务记录移除后读取消息快照。

## 实施顺序

1. 新增 PostgreSQL 保留期测试、死信确认顺序及真实 RabbitMQ 重投递测试，先确认缺失实现。
2. Flyway V6 增加摘要表和候选索引；新增 TaskRetentionSettings、TaskDeadLetters，补充 TaskStore 的有界清理。
3. TaskRuntime 接入死信专用消费者和数据库失败退避；TaskMaintenance 调用保留期清理，更新所有直接构造调用与角色测试。
4. 验证 API 请求幂等、消息快照及 SSE 在任务历史清理后不变；实际 RabbitMQ 队列释放、数据库失败不 ACK、重复投递不重复归档、正文不落库。
5. 完整后端测试、打包、原生双 Web/Worker 回归及入口探针；更新部署说明与 DEV_STATE.md，提交验证通过的改动。Part A 正文范围不变。

## 验证入口

`mvn.cmd -f backend/pom.xml '-Dtest=TaskRetentionTest,TaskDeadLettersTest,TaskMaintenanceTest,TaskRuntimeRoleTest,TaskBrokerTest,AiApiTest' test`；`mvn.cmd -f backend/pom.xml package`；`.\deploy\Verify-Persistence.ps1`；`.\deploy\Test-Local.ps1`。

依据：[RabbitMQ 确认机制](https://www.rabbitmq.com/docs/confirms)、[Quorum 显式归还与投递计数](https://www.rabbitmq.com/docs/quorum-queues#poison-message-handling)。
