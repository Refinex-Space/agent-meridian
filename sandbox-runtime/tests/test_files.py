"""文件接口：``/upload``、``/download``、``/list``、``/exists``。

请求按 AgentScope 2.0.4 ``Filesystem`` 的写法构造：multipart 字段名 ``file``、
``filename`` 为相对路径，其余接口把整个路径百分号编码（``/`` 编码为 ``%2F``）后拼在接口名之后。
"""

import os
from pathlib import Path
from urllib.parse import quote

import pytest
from fastapi.testclient import TestClient


def agentscope_multipart(filename: str, content: bytes) -> tuple[bytes, str]:
    """按扩展 ``Filesystem.buildMultipartBody`` 构造请求体，返回请求体与 Content-Type。"""
    boundary = "----AgentScopeSandbox123456789"
    prefix = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'
        "Content-Type: application/octet-stream\r\n\r\n"
    ).encode()
    suffix = f"\r\n--{boundary}--\r\n".encode()
    return prefix + content + suffix, f"multipart/form-data; boundary={boundary}"


def encode(path: str) -> str:
    """扩展的 ``percentEncode``：URLEncoder 编码后把 ``+`` 换成 ``%20``。"""
    return quote(path, safe="")


def upload(client: TestClient, filename: str, content: bytes) -> int:
    body, content_type = agentscope_multipart(filename, content)
    return client.post("/upload", content=body, headers={"Content-Type": content_type}).status_code


def test_upload_creates_parent_directories(client: TestClient, workspace: Path) -> None:
    data = bytes(range(256)) * 10

    assert upload(client, ".agentscope-tmp/ws-hydrate-1.tar", data) == 200

    assert (workspace / ".agentscope-tmp" / "ws-hydrate-1.tar").read_bytes() == data


def test_upload_overwrites_existing_file(client: TestClient, workspace: Path) -> None:
    upload(client, "a.txt", b"first")
    upload(client, "a.txt", b"second")

    assert (workspace / "a.txt").read_bytes() == b"second"


@pytest.mark.parametrize("name", ["../escape.txt", "dir/../../escape.txt"])
def test_upload_rejects_escape(client: TestClient, workspace: Path, name: str) -> None:
    assert upload(client, name, b"x") == 403
    assert not (workspace.parent / "escape.txt").exists()


def test_upload_rejects_directory_target(client: TestClient, workspace: Path) -> None:
    (workspace / "d").mkdir()

    assert upload(client, "d", b"x") == 400


def test_download_returns_raw_bytes(client: TestClient, workspace: Path) -> None:
    (workspace / "sub dir").mkdir()
    (workspace / "sub dir" / "数据.bin").write_bytes(b"\x00\x01binary")

    resp = client.get("/download/" + encode("sub dir/数据.bin"))

    assert resp.status_code == 200
    assert resp.content == b"\x00\x01binary"
    assert resp.headers["content-type"] == "application/octet-stream"


def test_download_large_file(client: TestClient, workspace: Path) -> None:
    data = os.urandom(8 * 1024 * 1024)
    assert upload(client, "big.tar", data) == 200

    assert client.get("/download/big.tar").content == data


def test_download_missing_and_escape(client: TestClient, workspace: Path) -> None:
    (workspace.parent / "secret").write_text("s")
    os.symlink(workspace.parent / "secret", workspace / "link")

    assert client.get("/download/missing.txt").status_code == 404
    assert client.get("/download/" + encode("../secret")).status_code == 403
    assert client.get("/download/link").status_code == 403


def test_list_returns_file_entries(client: TestClient, workspace: Path) -> None:
    (workspace / "d").mkdir()
    (workspace / "d" / "f.txt").write_text("hello")
    (workspace / "d" / "child").mkdir()

    resp = client.get("/list/" + encode("d"))

    assert resp.status_code == 200
    entries = sorted(resp.json(), key=lambda e: e["name"])
    assert [(e["name"], e["type"]) for e in entries] == [("child", "directory"), ("f.txt", "file")]
    assert entries[1]["size"] == 5
    assert isinstance(entries[1]["mod_time"], float)
    assert set(entries[1]) == {"name", "size", "type", "mod_time"}


def test_list_missing_directory(client: TestClient) -> None:
    assert client.get("/list/nope").status_code == 404


def test_exists(client: TestClient, workspace: Path) -> None:
    (workspace / "x").mkdir()
    (workspace / "x" / "y.txt").write_text("1")

    assert client.get("/exists/" + encode("x/y.txt")).json() == {"path": "x/y.txt", "exists": True}
    assert client.get("/exists/" + encode("x")).json()["exists"] is True
    assert client.get("/exists/nope").json()["exists"] is False
    assert client.get("/exists/" + encode("../x")).status_code == 403
