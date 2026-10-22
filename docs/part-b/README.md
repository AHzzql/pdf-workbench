# PDF Extractor Part B

主文档：`PartB_Submission.docx`。正文、表格与设计图使用中文；课程模板页眉和技术标识保留原文。

## 内容与证据

- `diagrams/`：基础请求架构、模块关系和工作台导航的 SVG、PNG 与结构数据。
- `evidence/core-junit.xml`：2026-10-22 执行的 11 个核心测试类，共 93 个实例。
- `evidence/junit-summary.txt`：对应 Maven 执行摘要。
- `evidence/browser-summary.txt`：Nginx 入口 8 项工作台浏览器测试输出。
- `evidence/` 中的 PDF 与截图：测试夹具、签名与移除结果及工作台显示。
- `evidence/manifest.json`：证据、关联源码和原始套件报告的 SHA-256。

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
