"""HTTP 接口：响应格式、错误码与大小上限。"""

from fastapi.testclient import TestClient

from tests.conftest import FakeBackend


def test_parse_returns_blocks_markdown_and_heading_paths(
    client: TestClient, backend: FakeBackend
) -> None:
    response = client.post(
        "/v1/parse", files={"file": ("规定.pdf", b"%PDF-1.7", "application/pdf")}
    )

    assert response.status_code == 200
    body = response.json()
    assert body["parser"] == "fake"
    assert body["page_count"] == 2
    assert body["blocks"][0] == {
        "type": "heading",
        "text": "办公设备采购与领用规定",
        "markdown": None,
        "level": 1,
        "page": 1,
        "heading_path": ["办公设备采购与领用规定"],
    }
    assert body["blocks"][2]["heading_path"] == ["办公设备采购与领用规定", "一、适用范围"]
    assert body["blocks"][3]["markdown"] == "| 岗位 | 配置 |\n| --- | --- |"
    assert body["markdown"].startswith("# 办公设备采购与领用规定\n\n## 一、适用范围\n\n本规定")
    assert backend.paths[0].suffix == ".pdf"


def test_errors_map_to_status_codes(client: TestClient) -> None:
    assert (
        client.post("/v1/parse", files={"file": ("a.zip", b"x", "application/zip")}).status_code
        == 415
    )
    assert (
        client.post("/v1/parse", files={"file": ("a.bad", b"x", "application/pdf")}).status_code
        == 422
    )
    assert (
        client.post("/v1/parse", files={"file": ("a.pdf", b"", "application/pdf")}).status_code
        == 400
    )
    too_large = client.post("/v1/parse", files={"file": ("a.pdf", b"x" * 2048, "application/pdf")})
    assert too_large.status_code == 413
    assert client.post("/v1/parse").status_code == 422


def test_health(client: TestClient) -> None:
    assert client.get("/healthz").json() == {"status": "ok", "backend": "fake"}
