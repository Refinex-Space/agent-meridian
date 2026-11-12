# doc-parser

文档解析服务（Python FastAPI，容器内 8000 端口，本机 18084）：knowledge 把扫描件、带框线表格的 PDF 与图片交给它，返回带结构的块与 Markdown（ADR-0051）。默认后端 Docling，MinerU 可选；不保存文件、不调用其他服务。接口、镜像体积与内存、MinerU 的许可证见 `README.md`。

## 命令

- 检查（`scripts/dev/verify.sh` 也会跑）：`cd doc-parser && uv run ruff format --check . && uv run ruff check . && uv run mypy && uv run pytest -q -W error`（不需要模型的用例）
- 用模型跑扫描件与表格的用例：镜像内 `docker build --target test doc-parser`；本机要先 `uv run docling-tools models download layout tableformer rapidocr -o <目录>`，再 `HF_HUB_OFFLINE=1 DOC_PARSER_ARTIFACTS=<目录> uv run pytest -q -m models`
- 镜像：`docker build -t agent-meridian/doc-parser:dev doc-parser`（需要代理时加 `--build-arg HTTPS_PROXY=… --build-arg HTTP_PROXY=…`）；本机运行 `scripts/dev/run-doc-parser.sh`
- 改依赖：`uv lock`（`exclude-newer` 只取发布满两周的版本；Linux 的 torch 取 CPU 索引），提交 `uv.lock`

## 结构

- `src/doc_parser/app.py`：`/healthz`、`POST /v1/parse`（multipart `file`，按扩展名判断类型；415 不支持、422 损坏、413 过大、400 空文件），并发由 `DOC_PARSER_CONCURRENCY` 的信号量限制。
- `structure.py`：块（`heading`、`paragraph`、`list_item`、`table`）、按中文编号推断标题层级（`assign_levels`）、标题路径、去掉汉字之间的换行空格（`clean_text`）、Markdown 输出。
- `backends/docling_backend.py`：版面、TableFormer（快速）、RapidOCR（`DOC_PARSER_OCR_LANG`，默认 `ch`）；只取正文层的条目，页眉页脚不进入正文。`backends/mineru_backend.py`：调用 `mineru` 命令行、读 `*_content_list.json`。
- `settings.py`：`DOC_PARSER_BACKEND`、`DOC_PARSER_ARTIFACTS`、`DOC_PARSER_MAX_BYTES`、`DOC_PARSER_OCR_LANG`、`DOC_PARSER_CONCURRENCY`、`DOC_PARSER_MINERU_COMMAND`。

## 不变量

- 运行时不联网：镜像设 `HF_HUB_OFFLINE=1`，模型在构建时下载到 `/opt/doc-parser/models`（`DOC_PARSER_ARTIFACTS`）；新增模型要改 Dockerfile 的下载步骤与 README 的体积。
- 不校验服务 JWT，只在集群内网暴露，网络策略限定只有 knowledge 能访问（ADR-0051）；日志只记大小、页数与耗时，不记文件内容。
- 响应的字段与 knowledge 的 `DocParserClient` 对应（`blocks[].type/text/markdown/level/page`），改字段两边同时改。

## 易错点

- Docling 在 PDF 中给出的标题层级都是 1，层级以 `structure.assign_levels` 按编号推断的为准；"第一章 总则"中的空格会被 `clean_text` 去掉（与换行产生的空格无法区分）。
- Starlette 的测试客户端要求 `httpx2`（`httpx` 会触发弃用警告，`-W error` 下失败）。
- Ruff 的 `E501` 按显示宽度计，汉字算两列：中文 docstring 每行约 45 个字。
- 镜像以 uid 1000 运行，缓存目录在 `/tmp/home`；`apt` 经本机透明代理偶尔拿不到签名（Dockerfile 已加重试，仍失败就重跑构建）。
- 镜像必须有中文字体：Docling 渲染页面时没有中文字体会把汉字画成方框，版面识别随之错乱（正文被认成表格、表格阶段耗时数分钟）。
