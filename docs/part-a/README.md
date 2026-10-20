# PDF 文档处理系统 Part A

`PartA需求规格说明书.docx` 为主文档；`openapi.json` 为核心接口设计契约；`swagger/index.html` 为本地 Swagger 原型；`diagrams` 保存 SVG 图源、PNG 及节点连线数据。

可直接打开 `index.html` 查看交付目录。Swagger 使用本地资源，不依赖 CDN；执行请求时连接契约中配置的本机应用地址。

编辑需求与图形数据后，使用带 python-docx 和 Pillow 的 Python 运行 `tools/part_a/build.py` 重建，随后更新 Word 目录并重新渲染检查。
