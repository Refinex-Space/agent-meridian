"""测试夹具：注入替身后端的服务客户端。"""

from collections.abc import Iterator
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from doc_parser.app import create_app
from doc_parser.backends.base import ParseFailed, ParseResult, UnsupportedDocument
from doc_parser.settings import Settings
from doc_parser.structure import Block, assign_levels

FIXTURES = Path(__file__).parent / "fixtures"


class FakeBackend:
    """按扩展名返回固定结果的后端：``.bad`` 解析失败，``.zip`` 不支持。"""

    name = "fake"

    def __init__(self) -> None:
        self.paths: list[Path] = []

    def parse(self, path: Path) -> ParseResult:
        self.paths.append(path)
        if path.suffix == ".zip":
            raise UnsupportedDocument(path.suffix)
        if path.suffix == ".bad":
            raise ParseFailed("损坏")
        blocks = [
            Block("heading", "办公设备采购与领用规定", 1),
            Block("heading", "一、适用范围", 1),
            Block("paragraph", "本规定适用于各部门。", 1),
            Block("table", "岗位 配置", 2, markdown="| 岗位 | 配置 |\n| --- | --- |"),
        ]
        return ParseResult(parser=self.name, page_count=2, blocks=assign_levels(blocks))


@pytest.fixture
def backend() -> FakeBackend:
    """替身后端。"""
    return FakeBackend()


@pytest.fixture
def client(backend: FakeBackend) -> Iterator[TestClient]:
    """上限 1 KB 的服务客户端。"""
    settings = Settings(
        backend="fake",
        artifacts=None,
        max_bytes=1024,
        ocr_lang="ch",
        concurrency=1,
        mineru_command="mineru",
    )
    with TestClient(create_app(settings, backend)) as c:
        yield c
