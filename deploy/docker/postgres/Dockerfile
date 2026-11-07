# AgentMeridian 的 PostgreSQL 17 镜像：官方 pgvector 镜像加中文分词 zhparser（依赖 SCWS）。
# 架构设计「技术基线」：PostgreSQL 17、pgvector 0.8，中文全文检索用 zhparser。
#
# 构建时宿主 shell 的代理变量在容器里不可用。需要代理时使用 Docker Desktop 内置代理：
#   docker build --build-arg HTTPS_PROXY=http://http.docker.internal:3128 deploy/docker/postgres
# HTTP_PROXY、HTTPS_PROXY 是 Docker 预定义的构建参数，不需要在这里声明。

# pgvector 0.8.6（2026-08 发布）。0.8.7 发布不满两周，按编码规范「依赖管理」暂不采用。
ARG PGVECTOR_IMAGE=pgvector/pgvector:0.8.6-pg17-bookworm

FROM ${PGVECTOR_IMAGE} AS builder

# 源码包固定版本并校验 SHA-256，下载内容一旦变化就构建失败
ARG SCWS_VERSION=1.2.3
ARG SCWS_SHA256=60d50ac3dc42cff3c0b16cb1cfee47d8cb8c8baa142a58bc62854477b81f1af5
ARG ZHPARSER_VERSION=2.3
ARG ZHPARSER_SHA256=96fe19f1b72c922eab19ecfc305384381562ff840da4d3b77452e21fefe8f0d2

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        build-essential bzip2 ca-certificates curl "postgresql-server-dev-${PG_MAJOR}"; \
    rm -rf /var/lib/apt/lists/*

WORKDIR /build

RUN set -eux; \
    curl -fsSL -o scws.tar.bz2 "https://www.xunsearch.com/scws/down/scws-${SCWS_VERSION}.tar.bz2"; \
    echo "${SCWS_SHA256}  scws.tar.bz2" | sha256sum -c -; \
    tar -xjf scws.tar.bz2; \
    cd "scws-${SCWS_VERSION}"; \
    ./configure --prefix=/usr/local; \
    make -j"$(nproc)"; \
    make install

# with_llvm=no：不生成 JIT 的 bitcode，构建不依赖 clang；zhparser 不需要 JIT 内联
RUN set -eux; \
    curl -fsSL -o zhparser.tar.gz "https://github.com/amutu/zhparser/archive/refs/tags/v${ZHPARSER_VERSION}.tar.gz"; \
    echo "${ZHPARSER_SHA256}  zhparser.tar.gz" | sha256sum -c -; \
    tar -xzf zhparser.tar.gz; \
    cd "zhparser-${ZHPARSER_VERSION}"; \
    make -j"$(nproc)" with_llvm=no; \
    make install with_llvm=no

FROM ${PGVECTOR_IMAGE}

COPY --from=builder /usr/local/lib/libscws.so* /usr/local/lib/
COPY --from=builder /usr/lib/postgresql/${PG_MAJOR}/lib/zhparser.so /usr/lib/postgresql/${PG_MAJOR}/lib/
COPY --from=builder /usr/share/postgresql/${PG_MAJOR}/extension/zhparser* /usr/share/postgresql/${PG_MAJOR}/extension/
COPY --from=builder /usr/share/postgresql/${PG_MAJOR}/tsearch_data/dict.utf8.xdb /usr/share/postgresql/${PG_MAJOR}/tsearch_data/
COPY --from=builder /usr/share/postgresql/${PG_MAJOR}/tsearch_data/rules.utf8.ini /usr/share/postgresql/${PG_MAJOR}/tsearch_data/

RUN ldconfig

# 只在数据目录为空（首次启动）时执行
COPY initdb/ /docker-entrypoint-initdb.d/
