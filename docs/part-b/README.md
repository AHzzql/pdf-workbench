# PDF Extractor Part B

主文档：`PartB_Submission.docx`。正文、表格与设计图使用中文；课程模板页眉和技术标识保留原文。

## 内容与证据

- `diagrams/`：基础请求架构、模块关系和工作台导航的 SVG、PNG 与结构数据。
- `evidence/core-junit.xml`：2026-10-22 执行的 11 个核心测试类，共 93 个实例。
- `evidence/junit-summary.txt`：对应 Maven 执行摘要。
- `evidence/browser-summary.txt`：Nginx 入口 8 项工作台浏览器测试输出。
- `evidence/` 中的 PDF 与截图：测试夹具、签名与移除结果及工作台显示。
- `evidence/manifest.json`：证据、关联源码和原始套件报告的 SHA-256。

- `presentation/PartB_Demonstration.pptx`：12 页中文课堂演示，覆盖工作台流程、七类核心操作、架构、测试与本机运行范围。

## 重新构建文档

先运行核心测试及工作台浏览器测试，再执行证据采集。证据采集使用 `backend/target/part-b-core-tests.log` 和 `part-b-browser-tests.log`，所选测试类见 `tools/part_b/collect_evidence.py`。

文档工具需要 Python 的 python-docx、Pillow 和 pypdfium2，以及本机 Microsoft Word。

```powershell
python tools/part_b/collect_evidence.py
python tools/part_b/build.py
.\tools\part_b\render.ps1
python tools/part_b/review.py
python tools/part_b/check.py
```

Word 自动化更新目录和页码，生成的审阅 PDF、页面图和结构检查结果位于 `backend/target/part-b-qa`。原始提交模板保存在 `docs/templates/PartB-Submission-Template.docx`，生成器不修改原件。

## 软件与源码交付包

运行说明见 DELIVERY_README.md。交付 ZIP 生成于 backend/target/part-b-delivery/PDF_Extractor_PartB_Delivery.zip，伴随独立 SHA-256 文件；大体积可执行产物不纳入 Git。包内含 JAR、静态前端、Java/前端及测试源码、部署配置、英文 Part A、中文 Part B 和 12 页中文 PPT；不收录旧中文 Part A 成稿与本机运行目录。

生成与校验：

~~~powershell
python tools/part_b/test_delivery.py
python tools/part_b/build_delivery.py
python tools/part_b/build_delivery.py --verify backend/target/part-b-delivery/PDF_Extractor_PartB_Delivery.zip
~~~

打包前须完成前端构建和后端编译打包。工具校验 JAR 与本机编译目录一致，按版本控制文件清单收集源码，扫描当前运行凭据及私钥，并检查 ZIP 路径、CRC、文件长度和逐文件 SHA-256。

delivery/source-verification.json 保存独立解压目录的完整 Maven 构建结果：37 个测试类共 259 个实例，失败、错误与跳过均为 0；158 个应用类和资源与交付 JAR 一致，173 个实现及测试源文件与当前工作树一致。前端 33 项测试与静态构建通过。ZIP 原始 JAR 在独立 JVM、随机数据库模式中完成静态页、OpenAPI、PDF 文本提取及非法表达式 400 检查；解压后的凭据初始化和 Compose 配置检查通过。临时服务凭据未写入 ZIP，测试模式已删除。

tools/part_b/verify_delivery.ps1 复用本机支撑服务，在 backend/target/delivery-check/PDF_Extractor_PartB 解压目录及生成的 ZIP 上重复运行 JAR 检查；只创建和删除随机验证模式，不启动或替换现有容器。原始完整构建日志保存在独立解压目录的 build-verification.log，运行日志位于 backend/target/part-b-delivery/runtime.log。
