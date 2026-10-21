# PDF 文档处理系统 Part A

`PartA需求规格说明书.docx` 为主文档；`openapi.json` 为核心接口设计契约；`swagger/index.html` 为本地 Swagger 原型；`diagrams` 保存 SVG 图源、PNG 及节点连线数据。

可直接打开 `index.html` 查看交付目录。通过 Nginx 启动本机服务后，访问 `http://127.0.0.1:5173/part-a/`；Swagger 使用本地资源和同源 API，不依赖 CDN。

编辑需求与图形数据后，使用带 python-docx 和 Pillow 的 Python 运行 `tools/part_a/build.py` 重建。`tools/part_a/render.ps1` 通过本机 Word/WPS 的 Word.Application 接口更新目录并导出校核 PDF；`rasterize.py` 使用 pypdfium2 生成逐页图，`check.py` 检查结构、图源、需求及接口一致性。
