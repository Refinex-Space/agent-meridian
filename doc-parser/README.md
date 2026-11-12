# doc-parser

AgentMeridian 的文档解析服务：knowledge 把扫描件、带框线表格的 PDF 与图片交给它，它返回带结构的块（标题、段落、列表项、表格，带页码与标题路径）和 Markdown。Office、HTML、Markdown 与只有文字的 PDF 由 knowledge 自己用 Tika、PDFBox 解析，不经过本服务（ADR-0051）。

## 接口

`POST /v1/parse`，`multipart/form-data`，字段 `file`（扩展名决定类型：`.pdf`、`.png`、`.jpg`、`.jpeg`、`.tif`、`.tiff`、`.bmp`、`.webp`）。

```json
{
  "parser": "docling",
  "page_count": 2,
  "markdown": "# 办公设备采购与领用规定\n\n## 一、适用范围\n\n…",
  "blocks": [
    {"type": "heading", "text": "一、适用范围", "markdown": null, "level": 2, "page": 1,
     "heading_path": ["办公设备采购与领用规定", "一、适用范围"]},
    {"type": "table", "text": "岗位类别 计算机配置 …", "markdown": "| 岗位类别 | 计算机配置 | … |", "level": 0, "page": 1,
     "heading_path": ["办公设备采购与领用规定", "三、配置标准"]}
  ]
}
```

| 状态码 | 含义 |
|---|---|
| 200 | 解析成功 |
| 400 | 文件为空 |
| 413 | 超过 `DOC_PARSER_MAX_BYTES`（默认 100 MB） |
| 415 | 扩展名不是 PDF 或图片 |
| 422 | 文件无法解析（损坏、加密） |

`GET /healthz` 是存活检查。服务不校验服务 JWT，只能部署在集群内网，由网络策略限定只有 knowledge 能访问；它不保存文件，日志不记录文件内容。

标题层级：Docling 在 PDF 中给出的标题都是同一层，服务按中文编号推断层级——"第一编"＞"第一章"＞"第一节"＞"一、"＞"（一）"＞"1."（"1.1" 再下沉一层）＞"（1）"，文档中实际出现的种类依次排列；第一个编号标题之前的无编号标题（文档名）为第 1 层。汉字之间因换行产生的空格会被去掉。

## 后端

| 后端 | 启用 | 说明 |
|---|---|---|
| Docling（默认） | `DOC_PARSER_BACKEND=docling` | 版面模型、TableFormer（快速模式）、RapidOCR（`DOC_PARSER_OCR_LANG`，默认 `ch`，即中英文）。公式识别、图片分类与 VLM 模型不下载、不启用 |
| MinerU（可选） | 构建参数 `WITH_MINERU=true`，运行时 `DOC_PARSER_BACKEND=mineru` | 调用 `mineru` 命令行（pipeline 后端），读取 `*_content_list.json`；表格是 HTML |

MinerU 不在默认镜像中：它连同模型有数 GB，且使用 MinerU 自己的开源许可证（PyPI 上标注为 `LicenseRef-MinerU-Open-Source-License`，早期版本为 AGPL-3.0），商用与分发前要由使用方核对许可证条款。`WITH_MINERU=true` 时安装的版本不在 `uv.lock` 中锁定。

## 镜像

```bash
docker build -t agent-meridian/doc-parser:dev doc-parser          # 需要代理时加 --build-arg HTTPS_PROXY=… --build-arg HTTP_PROXY=…
docker build --target test doc-parser                              # 镜像内跑 pytest，含用固化模型解析扫描件与表格的用例
scripts/dev/run-doc-parser.sh                                      # 本机运行，http://localhost:18084
```

- 模型在构建时下载到 `/opt/doc-parser/models`（`docling-tools models download layout tableformer rapidocr`，约 730 MB），运行时 `HF_HUB_OFFLINE=1` 不联网。
- Linux 上的 torch 取 CPU 版索引，不带 CUDA。镜像以 uid 1000 运行，缓存在 `/tmp/home`。
- 依赖只取发布满两周的版本（`pyproject.toml` 的 `exclude-newer`），版本以 `uv.lock` 为准：Docling 2.144.0、RapidOCR 3.10.0、torch 2.15.0。

## 体积与资源（2026-11-12 实测，Docker Desktop，linux/arm64）

| 项 | 实测 |
|---|---|
| 镜像体积 | 4.12 GB（Python 依赖与 CPU 版 torch 约 3.2 GB，模型约 730 MB，中文字体约 5 MB） |
| 空闲内存 | 约 300 MB |
| 解析时的内存峰值 | 约 1.6 GB（加载版面、表格、OCR 模型之后） |
| 单页扫描件 | 首次约 11 秒（含加载模型），之后约 9 秒 |
| 两页带框线表格的 PDF | 约 4 秒 |

镜像必须带中文字体（`fonts-wqy-microhei`）：Docling 渲染页面图像时按字体名取系统字体，没有中文字体时汉字画成方框，版面模型会把正文认成表格，TableFormer 也随之在几十个"表格"上耗费数分钟（实测 190 至 538 秒）。

建议内存限额不低于 3 GB，`DOC_PARSER_CONCURRENCY` 默认 1（每多一个并发约多占 1.5 GB）；`OMP_NUM_THREADS` 默认 4，与 CPU 限额一致。knowledge 调它的读取超时默认 15 分钟（`meridian.knowledge.doc-parser.timeout`），留给几十页的扫描件。

## 配置

| 环境变量 | 默认 | 说明 |
|---|---|---|
| `DOC_PARSER_BACKEND` | `docling` | `docling` 或 `mineru` |
| `DOC_PARSER_ARTIFACTS` | 镜像中为 `/opt/doc-parser/models` | Docling 模型目录；为空时 Docling 按需下载 |
| `DOC_PARSER_OCR_LANG` | `ch` | RapidOCR 的语言（PP-OCR 代码） |
| `DOC_PARSER_MAX_BYTES` | 104857600 | 单个文件的大小上限 |
| `DOC_PARSER_CONCURRENCY` | 1 | 同时进行的解析数 |
| `DOC_PARSER_MINERU_COMMAND` | `mineru` | MinerU 命令行 |
