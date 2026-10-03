# PDF 文档处理系统软件设计

## 1. 设计范围与技术选择

本设计对应 `01-requirements.md` 的 FR-01～FR-15，涵盖工具类、数据模型、处理算法及 HTTP 接口。

后端采用 Java 21、Spring Boot、Maven，PDF 读写使用 PDFBox 3，证书和 CMS 使用 Bouncy Castle，接口说明使用 springdoc-openapi。测试使用 JUnit 5 和 Spring Boot Test。前端沿用 Vue 3、TypeScript、Vite、Vue Router、PDF.js，不引入状态管理框架或服务器数据库。依赖版本由 Maven 配置和 package-lock 锁定。

选择理由与取舍：

- 使用一个后端应用而非多服务：任务是本地 PDF 文件处理，不需要网络服务拆分；代价是单机资源有限，须控制输入与同时处理数量。
- 保持静态工具层独立：提供静态方法及独立调用入口；Service 只组织上传及输出，不能把算法复制到 Web 层。
- 使用 PDFBox 和 Bouncy Castle：分别负责 PDF 结构及密码学，不自写 PDF 解析器或签名算法；简单表格与阅读顺序仍需编写项目算法。
- 不设置账户和数据库：上传文件只在请求期间存在，避免为了界面历史记录增加持久化系统。

## 2. 工程结构与职责

```text
backend/
  pom.xml
  src/main/java/cn/edu/pdfworkbench/
    PdfWorkbenchApplication.java
    config/       OpenAPI 与处理上限配置
    controller/   HTTP 参数和响应
    service/      文件处理流程与临时资源管理
    model/        参数、文本位置、表格、图片及签名结果
    util/         独立静态 PDF 工具
    exception/    业务异常与统一 HTTP 错误转换
    example/      不启动 Spring 的调用示例
  src/main/resources/application.yml
  src/main/resources/fonts/   实际选用的中文字体及许可
  src/test/java/cn/edu/pdfworkbench/
frontend/
  src/api/         HTTP 调用
  src/components/  上传、PDF 预览、结果等共用组件
  src/composables/ 当前文件与处理状态
  src/router/      功能路由
  src/types/       接口数据类型
  src/views/       各功能页面
docs/
  01-requirements.md
  02-design.md
  images/          方案 A 界面图
```

前端保留标准 Vite 配置和 package-lock；后端由 IDEA 导入 Maven 工程启动，前端由 npm 启动。

调用方向为 `Controller → PdfService → 静态工具 → PDFBox / Bouncy Castle`。Controller 不读取底层 PDF 对象，Service 不识别字形或计算签名，工具层不依赖 MultipartFile、HTTP、Controller 或 Spring Bean。工具层仅返回普通 Java 对象或写出文件。

`PdfService` 负责通用文件校验与请求流程，不聚集各功能算法。Controller 按解析、文档修改、签名三组组织。共同的打开文档、页码、权限和输出路径校验以少量辅助方法复用。

## 3. 静态工具入口

所有下表工具类均为 `final`，构造器为 `private`，入口均为 `public static`。方法的 Javadoc 说明单位、返回值、异常及资源所有权。`Path` 表示本地文件，`password` 为现有 PDF 打开密码；输出路径不得指向输入文件。返回数据不得含仍依赖已关闭文档的 PDFBox 对象。

| 工具类 | 方法签名 | 对应需求 |
| --- | --- | --- |
| PdfTextUtils | `TextResult extract(Path input, String password, TextOptions options)` | FR-01～03 |
| PdfTextUtils | `List<SearchMatch> search(Path input, String password, SearchOptions options)` | FR-04 |
| PdfInfoUtils | `DocumentInfo read(Path input, String password)` | FR-05～07 |
| PdfTableUtils | `List<TableData> extract(Path input, String password, TableOptions options)` | FR-08 |
| PdfTableUtils | `void writeCsv(TableData table, Path output)` | FR-08 的导出呈现 |
| PdfImageUtils | `List<ImageData> extract(Path input, String password)` | FR-09 原始数据 |
| PdfImageUtils | `List<ImageExport> export(Path input, String password, Path directory, ImageFormat format)` | FR-09 PNG/JPG |
| PdfEncryptionUtils | `void encrypt(Path input, String password, Path output, EncryptionOptions options)` | FR-10 |
| PdfSignatureUtils | `void sign(Path input, String password, Path output, SigningOptions options)` | FR-11 |
| PdfSignatureUtils | `List<SignatureResult> verify(Path input, String password, List<X509Certificate> trustAnchors)` | FR-12 |
| PdfSignatureUtils | `int remove(Path input, String password, Path output)` | FR-13；返回移除数量 |
| PdfWatermarkUtils | `void addText(Path input, String password, Path output, TextWatermark options)` | FR-14 |
| PdfWatermarkUtils | `void addImage(Path input, String password, Path output, ImageWatermark options)` | FR-15 |

文件读取失败抛出 IOException；无效业务参数抛出明确的参数异常；密码、权限及证书错误使用带原因码的处理异常，保留 cause，不能返回空结果掩盖失败。空结果仅表示无文本、无表格、无图片或无签名等正常情况。方法不修改源文件；生成内容写入临时输出，成功后才替换目标，失败清理部分文件。

各能力提供一个对应示例类的 `main`，参数取自命令行路径；签名示例不硬编码私钥或密码。示例覆盖上表全部入口，验证阶段可在不启动 Spring 的环境运行。证书密码使用 `char[]` 接收并按所有权清理；不得重写参数对象的 `toString` 输出密码。普通 Java 字符串无法保证立即从内存抹除。

## 4. 数据对象及共同约定

- **页码**：对外从 1 开始。`PageSelection` 解析 `1,3-5`，去重、升序，缺省全部；非法、反向、0 和超界范围拒绝。内部访问 PDFBox 时才减 1。
- **位置**：`Rect(x, y, width, height)` 使用 PDF 默认用户空间坐标；以页面原始坐标轴为准，保留 CropBox、旋转角与 UserUnit。通常左下为原点、Y 向上，但裁剪框原点可能非零；坐标单位与 UserUnit 一起解释。
- **PageText**：页码、页面几何信息、规范化全文、`TextSpan` 列表、有序 `Paragraph` 列表。Span 包含文本、矩形、字体、有效字号、填充/描边颜色及渲染方式；未知颜色为空并附诊断，不默认为黑色。
- **SearchMatch**：页码、命中文字、在该页规范化全文中的起止偏移、关联矩形列表。全文与搜索必须使用同一份字符映射，偏移按 Java UTF-16 索引说明。
- **DocumentInfo**：标准信息、自定义 Info 字段、XMP、树形 `Bookmark`、按页的 `AnnotationData`。书签不可解析页码为空，附目标类型；高亮保留四边形而非只保留总包围框。
- **TableData**：页码、表格范围、行列数和矩形单元格数组。空格是空字符串，不能删除列；跨行文字用换行保留。
- **ImageData**：编号、原始编码字节、过滤器信息、像素尺寸、来源对象和出现记录。原始流可能不是独立 PNG/JPG，使用原始数据扩展名并附过滤器信息。`ImageExport` 返回实际导出文件及诊断，不再携带大块字节。
- **EncryptionOptions**：新用户/所有者密码、密钥位数、打印/复制/修改三个布尔项；有效位数只有 128、256。
- **SignatureResult**：签名名称、签署者、算法、`integrity`、`hasLaterRevision`、`certificateValidity`、`trustStatus`、`revocationChecked` 与原因。各维度独立返回状态及原因。
- **水印参数**：目标页、默认用户空间位置、角度、透明度；文字另有内容、字号、RGB；图片另有路径和等比边界尺寸。中文字体由项目配置引用，不接受任意服务器路径。

参数对象尽量使用 Java record，但含密码的对象不得使用自动生成的敏感 `toString`。模型只承载实际算法及接口所需字段。

## 5. 核心处理方法

### 5.1 文本、样式与坐标

通过 `Loader.loadPDF` 打开文件，使用 `PDFTextStripper` 提取字符。捕获 `TextPosition` 的字符、字体、矩阵和尺寸；颜色不属于 TextPosition，需在字形处理时读取当前图形状态，区分填充/描边及不可见文字。转换到 RGB 仅用于显示，保留原颜色空间信息；不可转换时记录诊断。

`getXDirAdj` 等方向调整坐标不能直接与未调整的页面坐标混用。采用字形矩阵和边界形成默认用户空间矩形，随后由页面几何信息转换到前端。PDF.js 使用页面 viewport 的变换矩阵将 PDF 点映射到 canvas，再叠加缩放与设备像素比；以裁剪、90/180/270 度旋转、非默认 UserUnit 样本验证。

字形矩形通常是字体度量的近似边界，不等于字形实际墨迹像素；界面用于定位而非排版重建。字号区分声明值和文本矩阵缩放后的有效尺寸，测试使用矩阵已知的文本进行核对。

先按相近基线组合文字行，再按明显的竖直空白带识别规则双栏，栏内从上到下、行内从左到右。利用行距及缩进判断段落，合并时记录字符到原始矩形的映射。固定单一 Y 排序不能正确处理双栏，因此必须有双栏反例测试；复杂版式输出文字位置及阅读顺序诊断。

搜索对上述规范化全文逐页匹配，不另跑一套文本算法。采用 RE2/J 限制灾难性回溯风险；前端和 OpenAPI 明确不支持反向引用、前后查找等语法，非法模式与无命中分开返回。换行、连字及补充字符均需有映射测试，不跨页面匹配。

### 5.2 文档信息

标准及自定义元数据读取 Info 字典；XMP 单独处理，XML 解析禁止外部实体和网络资源。遍历 Outline 保留子节点及兄弟顺序，解析直接目的地和命名目的地；使用访问集合避免循环，书签最多展开 64 层；XMP 大于 1 MiB 或不符合安全 XML 规则时返回诊断。读取页面注释，高亮读取 QuadPoints，备注读取内容、作者和矩形。只解释信息，不执行 JavaScript、打开附件或跟随外部链接。

### 5.3 简单表格

使用 `PDFGraphicsStreamEngine` 收集应用变换后的水平/垂直线段，包含矩形边框；按小容差合并重叠线段，交点形成候选矩形网格。只有边界闭合且行列规则的区域才作为简单表格，不把所有对齐文字当表格。容差集中配置并用细线及断开线样本确定。

使用与文本模块相同的文字坐标，将文字中心分配到单元格；边界文字按最大相交面积判定，避免重复分配。单元格内部按阅读顺序拼接，空单元格保留。复杂合并单元格与跨页表格明确不支持。CSV 使用 UTF-8，按 CSV 引号与换行规则转义；表格原始结果不改写，以电子表格打开的安全导出对公式前缀单独处理并在接口注明。

### 5.4 图片

同时检查页面资源、递归 Form XObject 和实际内容流中的内联图片，覆盖注释外观与软蒙版等可达图片资源；以对象标识去重并设置访问集合避免循环。内容流记录每次绘制位置，资源扫描补充未绘制资源。

外部图像资源保留原始编码流及过滤器说明；内联图保留其数据和参数。解码时使用 PDFBox 图像能力，不把压缩流直接当 PNG。PNG 保留透明度，JPG 在白底上合成后编码。提取 raw 与导出均在关闭文档前完成；不返回依赖原 PDStream 的懒对象。解码像素上限及累计字节限制由配置控制，无法解码的条目给出独立诊断，结果区分完整导出和部分导出。

### 5.5 加密

使用 `AccessPermission` 设置打印、复制、修改及相关细分权限，禁止打印时同时关闭高质量打印，禁止修改时对应注释、表单及组装权限按统一规则限制，避免只关一个位。使用 `StandardProtectionPolicy(ownerPassword, userPassword, permissions)`，选择 AES 和 128/256 位后保护并保存新文件。AES 128 密码限 32 个 Latin-1 字符；AES 256 密码的 UTF-8 编码长度不超过 127 字节。

用户密码打开只取得文档授权范围，所有者密码取得管理权限；读取和修改工具都检查对应权限，不能因为库允许访问对象就绕过权限判断。所有者身份依据解密权限的只读标记判断，不能以权限位全部开启作为身份依据。验收时读取实际加密字典及 AccessPermission，分别尝试用户和所有者密码。加密会改变文件字节，已有签名时拒绝直接加密。

### 5.6 证书签名与验证

读取 PKCS#12 密钥库，选择私钥条目并检查证书有效期和适用密钥用途；多个条目要求明确 alias，初始支持 RSA/SHA-256。PDFBox 创建 `PDSignature` 并预留 Contents，Bouncy Castle 构建包含签署者证书链的分离式 CMS，签署 PDFBox 提供的待签字节，增量保存到新文件。既有认证签名的 DocMDP/FieldMDP 必须检查；不支持的修改约束应拒绝。

验签先校验 ByteRange 非负、递增、无越界，缺口对应 Contents；再读取被签署字节和 CMS，验证签署者摘要及签名。原签名覆盖旧修订且存在合法追加内容时，完整性可通过，但 `hasLaterRevision=true`。篡改已签范围时完整性失败，不用 HTTP 500 表示普通验签失败。

证书当前有效期与 CMS 验证分开。信任采用调用者明确提供的根证书和 PKIX 链验证，不将自签证书默认当可信；未提供信任根输出未评估。签署时间字段可被签署者声明，不能替代可信时间戳；本项目不执行在线吊销检查，revocationChecked 返回 false。链验证与原始签名检查的边界参照 [PDFBox 官方验签示例](https://github.com/apache/pdfbox/blob/trunk/examples/src/main/java/org/apache/pdfbox/examples/signature/ShowSignature.java)。

移除签名时遍历 AcroForm 字段树，删除签名字段及页面 Widget，清理对应认证签名引用和签名标志，保留非签名字段和普通注释。采用新文件完整重写而非追加一个隐藏签名的修订，避免旧签名仍可见于输出结构。必须重新打开检查字段、页面控件和相关字典，并由独立签名检查工具复核；仅 `getSignatureDictionaries()` 为空不足以证明没有遗留引用。此功能取消新副本的签名效力，不是恢复签名前原始文件。

### 5.7 水印

在指定页使用 `PDPageContentStream` 的 APPEND 模式，保存/恢复图形状态，设置透明度和旋转矩阵后绘制，不覆盖原内容流。文字使用可嵌入且覆盖中文的项目字体；字体缺字应报明确错误。字体许可文件随资源保存。

图片使用 PDImageXObject，按目标宽高边界等比缩放，保留 alpha；两个水印入口共用页码、透明度、输出文件校验。遇到 CropBox 偏移和页面旋转时按第 4 节坐标约定处理。已有签名的 PDF 不直接加水印。

PDFBox 3 的加载与保存约束按 [官方迁移说明](https://pdfbox.apache.org/3.0/migration.html)执行：使用 Loader，文档关闭前完成数据读取，输入路径不能作为保存输出路径。

## 6. HTTP 接口与异常

接口前缀 `/api/v1/pdf`，下列业务接口使用 POST，上传为 `multipart/form-data`。通用字段为 `file`、可选 `password`；适用的接口接收可选 `pages`。字段不放到 URL，二进制响应使用附件文件名且移除路径信息。

| 路径 | 其他主要输入 | 成功响应 |
| --- | --- | --- |
| `/text` | 页码、段落选项 | TextResult JSON |
| `/text/search` | pattern、正则 flags | SearchMatch 数组 |
| `/info` | 无 | DocumentInfo JSON |
| `/tables` | 可选区域 | TableData 数组 |
| `/tables/csv` | tableIndex、可选区域 | CSV |
| `/images` | PNG/JPG 格式 | ZIP：原始流、导出图片、来源清单 |
| `/encrypt` | 新密码、keyLength、三个权限 | PDF |
| `/signatures/sign` | certificate、certificatePassword、alias、reason、location | PDF |
| `/signatures/verify` | 可选 trustCertificates | SignatureResult 数组 |
| `/signatures/remove` | confirmRemoval=true | PDF |
| `/watermark` | type=TEXT/IMAGE、内容或图片及几何参数 | PDF |

移除确认字段用于 Web 交互保护。元数据和文本接口直接返回对应数据对象。所有默认值、数值范围、枚举、Content-Type 和失败响应由实际参数类型和 Swagger 注解描述。OpenAPI 描述由 `/v3/api-docs` 提供，Swagger UI 展示接口及请求表单。

统一错误包含 HTTP status、稳定 code、可理解 message 和可选字段错误；不返回堆栈、磁盘路径或敏感参数。参数和正则错误为 400，密码/权限错误为 403，容量超限为 413，不支持的上传格式为 415，PDF 或证书不可解析为 422，并发容量已满为 429；未预期故障为 500。验签“不通过”和无命中是成功处理结果，不误映射成系统异常。

处理流程：校验大小及实际文件格式 → 请求级临时目录 → 调用工具 → 检查输出 → 返回结果/附件 → 释放文档、流与目录。请求中途失败、下载断开也要清理；异步流式响应必须在发送完成后才删除输出。工具内部使用 try-with-resources，不能依赖垃圾回收释放 PDF 文件句柄。

资源限制初值：PDF 50 MiB、200 页，总请求 70 MiB，累计输出 200 MiB，同时处理 2 个请求；图片另设解码像素上限。限制项集中配置，分别验证边界输入及超限响应。

## 7. 前端工作台

使用方案 A 的侧边导航、PDF 预览、参数面板和结果区。`docs/images/01-upload.png`、`02-text.png`、`03-table.png`、`04-signature.png` 为界面设计图，分别对应上传、文本、表格和签名页面。

文件状态为未选择、可处理、处理中、成功、失败；预览加载失败与服务端处理失败分开表示。处理期间防止重复提交，更换文件时取消请求或忽略旧响应，清空旧结果并释放 Blob URL；销毁 PDF.js loading task、文档和 worker。签名结果分别显示完整性、后续修订、有效期和信任，未评估用中性状态而非绿色通过。

结果表格使用白底和轻边框；只有可实际执行的结果才出现导出入口。组件只处理展示与交互，api 层负责 multipart 和错误解析，composable 管理当前文件与状态。密码只保留在当前表单内存，不进 localStorage、路由或日志。基本键盘操作、焦点和窄屏布局纳入浏览器验证。

## 8. 测试计划

每个核心入口覆盖正常、边界和非法输入，测试随模块实现开展，集成阶段执行全量回归。

| 对象 | 正常验证 | 边界与非法输入 |
| --- | --- | --- |
| 文本 extract | 单/多页、中英文、样式坐标、单/双栏 | 空白页、裁剪旋转、缺失映射、页码错误、损坏 PDF |
| 文本 search | 中文、多个命中、跨行 | 无命中、空表达式、非法/不支持模式、补充字符偏移 |
| 信息 read | 书签层级、命名目标、标准/自定义元数据、高亮/备注 | 无信息、无效目标、循环引用、损坏 XMP |
| 表格 extract / writeCsv | 规则表、中文、多行、导出后解析比对 | 空单元格、无表格、引号换行、无效区域、不可写输出 |
| 图片 extract / export | 单/多图片、嵌套、内联、原始流、PNG/JPG 像素 | 无图片、透明图、重复/未绘制资源、坏图片、输出失败 |
| encrypt | AES 128/256、两类密码、独立权限位 | 错误原密码、非法位数、相同新密码、已有签名、源输出同路径 |
| sign | 测试证书签署并独立验证 | 错误密码、无私钥、过期、多个条目、限制修改的 PDF |
| verify | 完整签名、可信链 | 无签名、篡改、增量修订、过期、无根、不可信链、非法 ByteRange |
| remove | 移除全部签名，普通字段保留 | 无签名、多签名、受限权限、残留 Widget/Perms、损坏文件 |
| addText / addImage | 中文、PNG/JPG、定位/角度/透明度 | 未选页不变、旋转页、字体缺字、非法尺寸/透明度、已签文档 |
| HTTP 与 Swagger | 每条路径实际请求，文档可访问，下载文件可重新打开 | 字段缺失、格式伪装、超限、密码错误、断开连接、临时目录清理 |
| Vue 工作台 | 选择文件→操作→结果→下载 | 无文件、重复提交、更换文件的旧响应、空结果、失败恢复 |

测试样本在测试运行时构造，包含确定的坐标、字体、颜色及表格矩阵。中文测试字体采用 [Noto Sans SC](https://github.com/google/fonts/tree/main/ofl/notosanssc)，字体及 OFL 许可保存在 backend/src/test/resources/fonts。中文字体和证书来源可追溯；证书私钥只用于测试临时目录，不提交生产私钥。对签名使用 Bouncy Castle 验证之外的独立检查结果，对水印渲染比对，对表格用已知矩阵，对图片解码并比较像素；期望值不能调用被测算法生成。

单元测试证明工具可以脱离 Spring；接口测试同时验证状态码、响应字段、真实文件内容和失败后无残留；至少一次启动真实 HTTP 服务检查 Swagger 和下载。实际执行 `mvn test`、`mvn package`，记录命令、JDK、依赖、测试数及结果。前端执行类型检查、测试及生产构建，再进行真实浏览器操作。

测试报告记录执行环境、用例、期望与实际结果、证据、缺陷及复测。验收追踪表按需求 ID 对应实现类、静态方法、接口、示例和测试。
