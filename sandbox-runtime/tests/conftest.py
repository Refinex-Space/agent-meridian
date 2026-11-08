"""测试夹具：以临时目录为工作区根的执行服务客户端。"""

from collections.abc import Iterator
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from sandbox_runtime.app import create_app
from sandbox_runtime.settings import Settings


@pytest.fixture
def workspace(tmp_path: Path) -> Path:
    """工作区根目录。"""
    root = tmp_path / "workspace"
    root.mkdir()
    return root


@pytest.fixture
def client(workspace: Path) -> Iterator[TestClient]:
    """超时 5 秒、输出上限 64 KB 的服务客户端。"""
    settings = Settings(base_dir=str(workspace), exec_timeout_seconds=5, output_limit_bytes=65536)
    with TestClient(create_app(settings)) as c:
        yield c
