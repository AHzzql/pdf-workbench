# 开发状态

M0～M5 已实现文本/信息、规则表格、图片、AES 加密及数字签名工具，尚无业务 HTTP 接口和 Vue 前端。新增 PdfSignatureUtils、SigningMaterial、SignatureVerifier 与签名模型，采用 RSA/SHA-256 分离式 CMS 增量签署；完整性、签后修订、证书当前有效期与信任分别返回，无在线吊销查询。签名新增 7 项测试覆盖多签名、篡改、非法 ByteRange、不可信证书、过期证书拒绝、密码/别名错误、加密权限及普通表单/批注保留；JDK 25、Maven 3.9.15、Java 21 编译目标下 mvn package 成功，全量 65 项通过。pypdf、asn1crypto、cryptography 独立验证已生成文件的 SHA-256 摘要、RSA 签名及移除后的字段/控件通过，结果在 backend/target/verification。完整重写加密 PDF 无法恢复双密码策略，移除入口明确拒绝加密输入，不静默解除加密；该边界已同步设计。文本字形框为近似值，复杂阅读顺序和复杂表格不支持；中文字体 cmap 14 警告仍存在但中文测试通过。下一步 M6 水印，随后 M7～M10 接口、前端、示例、联调及正式文档；证书链和异常结构仍需扩展回归。旧需求 Word 上次因占用未清理。
