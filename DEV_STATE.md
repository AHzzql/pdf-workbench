# 开发状态

M0～M4 已实现并验证，当前覆盖工程初始化、文本/文档信息、规则表格、图片及 AES 加密。新增 PdfEncryptionUtils 与 EncryptionOptions，支持 AES 128/256 和打印、复制、修改的独立权限、重新加密的所有者身份校验、已签文档拒绝及临时输出清理；测试发现 AccessPermission.isOwnerPermission 在用户所有权限开放时也为 true，已改为检查解密权限只读标记。AES 128 密码限 32 个 Latin-1 字符，AES 256 限 127 个 UTF-8 字节，设计已同步。JDK 25、Maven 3.9.15、编译目标 Java 21；最新 mvn package 成功，58 项测试全部通过，其中加密 20 项、图片 6 项。下一项 M5 证书签名、验签与移除，M6～M10 及业务 HTTP/Vue 前端尚未完成。文本字形框为度量近似，复杂版式及非横排顺序有限，表格仅支持规则轴向网格；中文字体 format 14 cmap 忽略警告仍存在但已测中文通过。旧需求 Word 上次因占用未清理。
