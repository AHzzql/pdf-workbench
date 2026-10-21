# Elasticsearch 文档检索迁移

## 目标与边界

以 Elasticsearch 替换嵌入式 Lucene，保留现有分块、1024 维嵌入、BM25/向量双路召回、Java RRF 与重排。数据库继续承担文档归属和删除状态，正则搜索保持原接口。使用已有 JDK HTTP 客户端与 Jackson，不增加第二套检索 SDK 或双写索引。

## 实施顺序

1. 新增 ElasticsearchSettings 与 ElasticsearchIndex：认证、连接和请求超时、稳定错误、单分片 CJK/向量映射、共享版本化别名、批量写入及逐项错误检查、空间/文档/代次过滤、清理接口。
2. Flyway V2 在 ai_documents 增加就绪代次、构建令牌和数据库租约。每次构建使用独立代次；数据库条件更新只有在令牌、租约、未删除状态均满足时才能发布就绪指针。构建失败不能将部分索引暴露给查询。
3. AiDocuments/AiRetrieval 改用共享索引和数据库就绪代次，删除先写墓碑并撤销构建令牌。旧本机索引只保留删除兼容，不再创建或查询，移除 Lucene 依赖。
4. TestServices 与原生启动脚本接入独立测试索引和忽略文件中的凭据；结束时只删除具备随机测试前缀的索引。
5. 使用真实 ES 与 PG 验证中英文、向量过滤、相同块标识跨空间/文档隔离、部分失败、重建代次切换、过期构建令牌、墓碑竞争及独立客户端重读；外部模型使用合成向量。执行完整后端、前端与双入口回归。

## 故障边界

会话与原有 PDF 基础接口不依赖 ES。搜索服务不可用返回 SEARCH_UNAVAILABLE，不返回空列表。单个文档构建租约为 10 分钟，在每批嵌入前后续约；失去令牌的构建不能发布就绪状态。未就绪代次可残留为派生数据，但不参与查询；崩溃后的异步清理、重建调度统一由后续 Outbox/Worker 处理。

## 验证入口

- `mvn.cmd -f backend/pom.xml test`
- `.\deploy\Verify-Persistence.ps1`
- `.\deploy\Test-Local.ps1`
- `npm --prefix frontend run test:production`
- `npm --prefix frontend run test:gateway`
