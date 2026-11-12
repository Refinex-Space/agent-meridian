# AgentMeridian 文档解析服务：Debian slim、非 root（uid 1000），uv 管理的 Python 3.13，Docling（CPU 版 torch）与 RapidOCR，
# 服务监听 8000。版面、TableFormer 与 RapidOCR 的模型在构建时下载到 /opt/doc-parser/models 并固化，运行时 HF_HUB_OFFLINE=1
# 不联网（ADR-0051）。可在 linux/arm64 与 linux/amd64 上构建。
#
#   构建：docker build -t agent-meridian/doc-parser:dev doc-parser
#         需要代理时加 --build-arg HTTPS_PROXY=http://http.docker.internal:3128 --build-arg HTTP_PROXY=…
#   测试：docker build --target test doc-parser     （在镜像内以 uid 1000 跑 pytest，含用固化模型解析扫描件与表格的用例）
#   MinerU：--build-arg WITH_MINERU=true 另装 MinerU（体积大、许可证需核对，见 README），运行时设 DOC_PARSER_BACKEND=mineru
ARG DEBIAN_IMAGE=debian:trixie-slim
ARG UV_IMAGE=ghcr.io/astral-sh/uv:0.11.8

FROM ${UV_IMAGE} AS uv

FROM ${DEBIAN_IMAGE} AS runtime
ARG WITH_MINERU=false
RUN apt-get -o Acquire::Retries=5 update \
    && apt-get -o Acquire::Retries=5 install -y --no-install-recommends ca-certificates libglib2.0-0 tini \
    && rm -rf /var/lib/apt/lists/*
COPY --from=uv /uv /uvx /usr/local/bin/

ENV UV_PYTHON_INSTALL_DIR=/opt/doc-parser/python \
    UV_PYTHON_PREFERENCE=only-managed \
    UV_PROJECT_ENVIRONMENT=/opt/doc-parser/venv \
    UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy
WORKDIR /opt/doc-parser/service
COPY pyproject.toml uv.lock ./
RUN --mount=type=cache,target=/root/.cache/uv \
    uv python install 3.13 \
    && uv sync --frozen --no-dev \
    && if [ "$WITH_MINERU" = "true" ]; then uv pip install --python /opt/doc-parser/venv/bin/python "mineru[core]"; fi
# 只下载 OCR 与版面解析必需的模型：版面、表格结构、RapidOCR（中英文）
RUN /opt/doc-parser/venv/bin/docling-tools models download layout tableformer rapidocr -o /opt/doc-parser/models \
    && chmod -R a+rX /opt/doc-parser/models
# 中文字体：Docling 自己渲染页面图像时按字体名用系统字体，没有中文字体时汉字画成方框，版面模型把正文认成表格
# （2026-11-12 实测）。放在依赖与模型之后，改字体不重新下载它们
RUN apt-get -o Acquire::Retries=5 update \
    && apt-get -o Acquire::Retries=5 install -y --no-install-recommends fontconfig fonts-wqy-microhei \
    && rm -rf /var/lib/apt/lists/*
COPY src ./src

RUN groupadd --gid 1000 parser && useradd --uid 1000 --gid 1000 --home-dir /tmp/home --no-create-home parser

ENV PATH=/opt/doc-parser/venv/bin:/usr/local/bin:/usr/bin:/bin \
    PYTHONPATH=/opt/doc-parser/service/src \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    HOME=/tmp/home \
    XDG_CACHE_HOME=/tmp/home/.cache \
    HF_HOME=/tmp/home/.cache/huggingface \
    HF_HUB_OFFLINE=1 \
    DOC_PARSER_ARTIFACTS=/opt/doc-parser/models \
    OMP_NUM_THREADS=4

USER 1000:1000
EXPOSE 8000
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=5 \
    CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/healthz', timeout=2)"]
ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["python", "-m", "doc_parser"]

FROM runtime AS test
USER root
RUN --mount=type=cache,target=/root/.cache/uv \
    cd /opt/doc-parser/service && uv sync --frozen --group dev
COPY tests /opt/doc-parser/service/tests
USER 1000:1000
RUN mkdir -p "$HOME" && cd /opt/doc-parser/service && pytest -q -p no:cacheprovider

# 默认构建目标是最终镜像；测试阶段只在 --target test 时执行
FROM runtime
