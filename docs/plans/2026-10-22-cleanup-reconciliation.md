# 删除任务补偿设计与实施计划

## 目标与边界

依赖服务持续故障可能耗尽 CLEAN 的单轮重试预算，业务文档保留 DELETING 状态。Worker 低频重新投递仍需删除的文档，不恢复生成或索引任务，不删除有会话引用或未标记删除的文档，不改变账户与聊天记录保留期。

## 方案

保留单轮有限重试及死信；独立调度线程每 60 秒领取最多 8 项失败满 15 分钟的 CLEAN。仅人工重试无法覆盖已消失的会话入口；取消重试上限会失去单轮故障边界。因此补偿沿用原任务，清空旧 deadline、重置 attempt，递增 dispatch_version 与 fence，QUEUED 与新 Outbox 同事务提交。旧死信保留，旧版本不能领取，新执行权仍受现有租约约束。

补偿使用与提交相同的事务级 advisory lock 和容量检查，按任务、文档顺序加锁，跳过被锁文档并重查 DELETING、会话引用及其他活动清理任务。不会在数据库事务内访问 S3 或 Elasticsearch；独立线程避免挤占任务心跳。数据库不可用时本轮结束，下一轮继续。

## 实施与验证

1. 扩充 `backend/src/test/java/cn/edu/pdfworkbench/TaskStoreTest.java`，以实际 PostgreSQL 验证失败冷却、引用/状态/空间隔离、旧版本 fencing、容量、重复执行和事务回滚；先运行确认缺口。
2. 在 `tasks/TaskStore.java` 增加有界补偿事务，Flyway V5 增加 FAILED CLEAN 的候选索引。
3. 新增 `tasks/TaskMaintenance.java` 独立调度及角色测试，Web 不运行补偿。
4. 执行后端完整测试及打包，运行原生多节点回归；记录实际结果，更新部署说明和 DEV_STATE.md，提交经验证的改动。

验证命令使用 PowerShell：`mvn.cmd -f backend/pom.xml '-Dtest=TaskStoreTest,TaskMaintenanceTest' test`、`mvn.cmd -f backend/pom.xml package`、`.\deploy\Verify-Persistence.ps1`。测试均使用随机数据库模式和独立队列，不修改实际账户或聊天数据。
