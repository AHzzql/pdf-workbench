# 核心 PDF 契约对齐实施方案

## 目标与边界

以 Part A 的 FR3、FR9、FR18、PAGE01、ERR01 和 API01—API11 为基准补齐核心返回数据和异常语义。保留字形定位、原接口路径、同步返回及既有扩展模块调用关系。数据库、对象存储与子进程迁移在核心契约稳定后实施。

## 文本与文档信息

在 TextResult.PageText 中增加不可变 blocks；同页同行且几何连续、字体字号、填充及描边状态相同的字形合并，保留原 spans 与 UTF-16 偏移。块边界为字形边界并集。DocumentInfo 增加 pageCount；高亮缺少 Contents 时通过 QuadPoints 对应原页文字，无法关联时返回诊断。ResultPanel 展示块的文字、位置、宽高和样式，以及后端总页数。

验证涉及 PdfTextUtilsTest、PdfInfoUtilsTest、AiChunkerTest 和 ResultPanel 组件测试，覆盖样式变化、行间及大间隔分块、旋转裁剪页、空文档、高亮无备注与空矩形。

## 错误与图片结果

在页码和正则入口保留稳定参数代码，区分缺失打开密码与错误密码。工作目录及输出创建失败返回存储错误，保留输入损坏错误类别。无图片返回 422 NO_IMAGES_FOUND；ZIP 清单增加 rawFile 和 EXPORTED/RAW_ONLY，响应头区分总数与成功转换数。处理失败、输出中断后清理工作区并释放并发名额。

验证涉及 PdfApiTest、PdfServiceLimitsTest、参数单元测试和浏览器错误反馈；核对 HTTP 状态、code、无凭据或本机路径泄漏及临时目录回收。

## 验签与界面

SignatureResult 的后续修订为可空布尔值，增加证书摘要。非法范围和结构或摘要不匹配为 INVALID；不支持类型或算法、缺少确定签署证书为 UNKNOWN；完整性与证书有效期、信任和修订保持独立。ResultPanel 显示无法验证及未知修订，不用未发现修改替代未知，并展示算法和证书信息。

验证正常、篡改、追加修订、缺失证书、未知类型、非法范围与证书有效期；检查运行时 OpenAPI 模型、前端单元测试和实际浏览器流程。通过全量回归、类型检查、代码检查和构建后提交。
