#!/usr/bin/env python3
"""M1 验收场景的后端预演（R2-A05）：以业务系统与管理员的身份，经 HTTP 跑通架构设计「M1 MVP」的八个验收场景的后端部分。

只用 Python 标准库。环境由 e2e/backend/env.sh 启动（control-plane、两个 runtime 节点、knowledge、脚本化模型服务、Keycloak、
基础设施，场景 2 另需 kind 集群 meridian 与已部署的沙箱）。每个场景新建自己的租户，互不影响；只打印 ID、状态与 PASS/FAIL，
不打印 API Key、令牌与凭据明文。

默认连接脚本化模型服务（脚本在 e2e/backend/model-scripts/）；--live 时场景 1、2、3 改用 DashScope 的真实模型
（需要 DASHSCOPE_API_KEY，模型取 MERIDIAN_LIVE_CHAT_MODEL、MERIDIAN_LIVE_EMBEDDING_MODEL），断言只写结构性条件。

用法：python3 e2e/backend/m1_backend.py [--live] [--only=1,3,5] [--skip=2]
"""

from __future__ import annotations

import argparse
import hashlib
import hmac
import io
import json
import os
import socket
import subprocess
import sys
import threading
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
# 脚本化模型服务读取的脚本目录（env.sh 从 model-scripts/ 复制），按本次运行生成的脚本写在这里
RUN_SCRIPTS = REPO / "e2e" / "backend" / ".run" / "model-scripts"
CONTROL_PLANE = os.environ.get("E2E_CONTROL_PLANE", "http://localhost:18081")
RUNTIME_A = os.environ.get("E2E_RUNTIME_A", "http://localhost:18080")
RUNTIME_B = os.environ.get("E2E_RUNTIME_B", "http://localhost:18086")
KNOWLEDGE = os.environ.get("E2E_KNOWLEDGE", "http://localhost:18083")
SCRIPTED = os.environ.get("E2E_SCRIPTED_MODEL", "http://localhost:18090/v1")
KEYCLOAK = os.environ.get("E2E_KEYCLOAK", "http://localhost:18082")
REDIS = ("127.0.0.1", int(os.environ.get("MERIDIAN_REDIS_PORT", "16379")))
DASHSCOPE = "https://dashscope.aliyuncs.com/compatible-mode/v1"
# 开发 realm 中 dev-admin 的用户标识（deploy/keycloak/meridian-dev-realm.json）：平台管理员，也作为新租户的所有者
DEV_ADMIN_SUB = "00000000-0000-4000-8000-000000000001"
MCP_TOKEN = "e2e-mcp-" + uuid.uuid4().hex

# 不经代理访问本机服务（工具环境的代理变量只对外网有效）
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


class Failure(AssertionError):
    """一个场景的断言失败。"""


def check(condition: bool, message: str) -> None:
    if not condition:
        raise Failure(message)


def log(message: str) -> None:
    print(message, flush=True)


# ---------------------------------------------------------------------------------------------------------------------
# HTTP

@dataclass
class Reply:
    status: int
    headers: dict
    body: bytes

    def json(self):
        return json.loads(self.body) if self.body else None


def http(method: str, url: str, token: str | None = None, body=None, headers: dict | None = None,
         timeout: float = 60) -> Reply:
    data = None
    all_headers = dict(headers or {})
    if token:
        all_headers["Authorization"] = "Bearer " + token
    if body is not None:
        if isinstance(body, (bytes, bytearray)):
            data = bytes(body)
        else:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            all_headers.setdefault("Content-Type", "application/json")
    request = urllib.request.Request(url, data=data, method=method, headers=all_headers)
    try:
        with OPENER.open(request, timeout=timeout) as response:
            return Reply(response.status, dict(response.headers), response.read())
    except urllib.error.HTTPError as e:
        return Reply(e.code, dict(e.headers), e.read())


def ok(reply: Reply, what: str):
    if reply.status // 100 != 2:
        raise Failure(f"{what}：HTTP {reply.status} {reply.body[:500].decode('utf-8', 'replace')}")
    return reply.json()


def multipart(fields: dict, files: dict) -> tuple[bytes, str]:
    boundary = "----e2e" + uuid.uuid4().hex
    out = io.BytesIO()
    for name, value in fields.items():
        out.write(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n".encode())
        out.write(value.encode("utf-8") + b"\r\n")
    for name, (filename, content) in files.items():
        out.write(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"; filename=\"{filename}\"\r\n"
                  f"Content-Type: application/octet-stream\r\n\r\n".encode())
        out.write(content + b"\r\n")
    out.write(f"--{boundary}--\r\n".encode())
    return out.getvalue(), "multipart/form-data; boundary=" + boundary


def console_token() -> str:
    """dev-admin 的 OIDC access token（Keycloak 开发 realm 的口令模式，scripts/dev/console-token.sh 的同一做法）。"""
    form = urllib.parse.urlencode({
        "grant_type": "password", "client_id": "meridian-dev-cli", "username": "dev-admin",
        "password": "meridian-dev", "scope": "openid"}).encode()
    reply = http("POST", f"{KEYCLOAK}/realms/meridian-dev/protocol/openid-connect/token", body=form,
                 headers={"Content-Type": "application/x-www-form-urlencoded"})
    return ok(reply, "从 Keycloak 取控制台令牌")["access_token"]


def redis_get(key: str) -> str | None:
    """最小的 RESP 客户端：读一个字符串键（预算计数）。"""
    with socket.create_connection(REDIS, timeout=5) as conn:
        command = f"*2\r\n$3\r\nGET\r\n${len(key.encode())}\r\n{key}\r\n".encode()
        conn.sendall(command)
        stream = conn.makefile("rb")
        header = stream.readline().decode().strip()
        if header == "$-1":
            return None
        check(header.startswith("$"), f"Redis 返回了意外的结果：{header}")
        value = stream.read(int(header[1:]))
        return value.decode()


# ---------------------------------------------------------------------------------------------------------------------
# 管理 API：每个场景一个新租户

@dataclass
class Tenant:
    token: str
    tenant: str
    project: str
    key: str
    key_id: str
    chat_model: str
    embedding_model: str | None
    base: str = field(init=False)
    project_base: str = field(init=False)

    def __post_init__(self):
        self.base = f"{CONTROL_PLANE}/admin/v1/tenants/{self.tenant}"
        self.project_base = f"{self.base}/projects/{self.project}"

    def admin(self, method: str, path: str, body=None, what: str | None = None):
        return ok(http(method, self.base + path, self.token, body), what or f"{method} {path}")

    def project_admin(self, method: str, path: str, body=None, what: str | None = None):
        return ok(http(method, self.project_base + path, self.token, body), what or f"{method} {path}")

    def environment(self, name: str, tier: str = "none", size: str | None = None) -> str:
        body = {"name": name, "tier": tier}
        if size:
            body["size"] = size
        return self.project_admin("POST", "/environments", body)["id"]

    def agent(self, name: str, *, tools: list[str], rules: list[dict] | None = None, mcp_servers: list[dict] | None = None,
              skills: list[dict] | None = None, knowledge_base_ids: list[str] | None = None,
              system_prompt: str = "你是企业内的通用助手，回答简洁。") -> str:
        agent_id = self.project_admin("POST", "/agents", {"name": name, "description": "e2e"})["id"]
        spec = {
            "model": {"model_id": self.chat_model, "max_output_tokens": 512},
            "system_prompt": system_prompt,
            "tools": tools,
            "mcp_servers": mcp_servers or [],
            "skills": skills or [],
            "subagents": [],
            "permissions": {"mode": "default", "rules": rules or []},
            "compaction": {"reserved_tokens": 20000},
            "plan_mode": False,
            "knowledge": {"knowledge_base_ids": knowledge_base_ids or [], "prefetch": False, "prefetch_top_k": 5},
            "max_iters": 10,
            "expose_thinking": False,
        }
        self.project_admin("POST", f"/agents/{agent_id}/versions", {"spec": spec}, "建智能体草稿")
        self.project_admin("POST", f"/agents/{agent_id}/versions/1:publish", None, "发布智能体")
        return agent_id

    # -- 公开 API（业务系统的 API Key）

    def api(self, method: str, url: str, body=None, headers: dict | None = None, timeout: float = 60) -> Reply:
        return http(method, url, self.key, body, headers, timeout)

    def session(self, agent: str, environment: str, runtime: str = RUNTIME_A, **extra) -> str:
        body = {"agent": agent, "environment": environment, **extra}
        return ok(self.api("POST", f"{runtime}/v1/sessions", body), "创建会话")["id"]

    def send(self, session_id: str, events: list[dict], runtime: str = RUNTIME_A) -> list[dict]:
        return ok(self.api("POST", f"{runtime}/v1/sessions/{session_id}/events", {"events": events}), "发送事件")["data"]

    def message(self, session_id: str, text: str, delivery: str = "queue", runtime: str = RUNTIME_A):
        return self.send(session_id, [{"type": "user.message", "delivery": delivery,
                                       "content": [{"type": "text", "text": text}]}], runtime)

    def events(self, session_id: str, runtime: str = RUNTIME_A) -> list[dict]:
        events, after = [], None
        while True:
            query = "limit=100" + (f"&after={after}" if after else "")
            page = ok(self.api("GET", f"{runtime}/v1/sessions/{session_id}/events?{query}"), "列出事件")
            events += page["data"]
            if not page["has_more"]:
                return events
            after = page["next_cursor"]

    def wait(self, session_id: str, predicate, what: str, timeout: float = 90, runtime: str = RUNTIME_A) -> list[dict]:
        deadline = time.monotonic() + timeout
        while True:
            events = self.events(session_id, runtime)
            errors = [e for e in events if e["type"] == "session.error" and e["data"]["retry_status"] == "terminal"]
            if predicate(events):
                return events
            if errors:
                raise Failure(f"{what}：会话出错 {errors[-1]['data']}")
            if time.monotonic() > deadline:
                raise Failure(f"{what}：{timeout} 秒内没有等到；最后的事件 {[e['type'] for e in events[-8:]]}")
            time.sleep(0.5)


def idle(stop_reason: str | None = None, after: int = 0):
    def predicate(events):
        return any(e["type"] == "session.status_idle" and e["seq"] > after
                   and (stop_reason is None or e["data"]["stop_reason"] == stop_reason) for e in events)
    return predicate


def requires_action(events) -> bool:
    return bool(events) and events[-1]["type"] == "session.status_requires_action"


def of_type(events: list[dict], type_: str) -> list[dict]:
    return [e for e in events if e["type"] == type_]


def tool_result_text(event: dict) -> str:
    return "".join(block.get("text", "") for block in event["data"]["content"])


def new_tenant(name: str, *, live: bool = False, embedding: bool = False, scopes: list[str] | None = None) -> Tenant:
    token = console_token()
    tenant = ok(http("POST", f"{CONTROL_PLANE}/admin/v1/tenants", token,
                     {"name": f"e2e {name} {uuid.uuid4().hex[:6]}", "owner": {"subject": DEV_ADMIN_SUB}}), "建租户")["id"]
    base = f"{CONTROL_PLANE}/admin/v1/tenants/{tenant}"
    project = ok(http("POST", f"{base}/projects", token, {"name": "e2e"}), "建项目")["id"]
    key = ok(http("POST", f"{base}/projects/{project}/api-keys", token, {
        "name": "e2e", "scopes": scopes or ["sessions:read", "sessions:write", "files:write", "knowledge:read",
                                            "knowledge:write", "models:invoke"]}), "签发 API Key")
    # 新租户默认零预算（ADR-0048）：四级都设为不限
    for level in ("tenant", "project", "api_key", "session"):
        ok(http("POST", f"{base}/budgets", token, {"level": level, "hard_limit_tokens": None}), "设置预算")
    secret_value = os.environ["DASHSCOPE_API_KEY"] if live else "scripted-" + uuid.uuid4().hex
    secret = ok(http("POST", f"{base}/secrets", token, {"name": "供应商", "value": secret_value}), "写入凭据")["id"]
    account = ok(http("POST", f"{base}/provider-accounts", token, {
        "name": "供应商", "provider": "openai_compatible", "base_url": DASHSCOPE if live else SCRIPTED,
        "secret_id": secret}), "建供应商账号")["id"]
    upstream = os.environ.get("MERIDIAN_LIVE_CHAT_MODEL", "qwen-plus") if live else "scripted"
    chat = ok(http("POST", f"{base}/models", token, {
        "provider_account_id": account, "name": "对话", "kind": "chat", "upstream_model": upstream,
        "context_window": 131072, "max_output_tokens": 1024}), "建对话模型")["id"]
    embedding_model = None
    if embedding:
        upstream_embedding = os.environ.get("MERIDIAN_LIVE_EMBEDDING_MODEL", "qwen3.7-text-embedding") if live \
            else "scripted-embedding"
        embedding_model = ok(http("POST", f"{base}/models", token, {
            "provider_account_id": account, "name": "向量", "kind": "embedding", "upstream_model": upstream_embedding,
            "context_window": 8192, "max_output_tokens": 1, "max_batch_size": 10}), "建 Embedding 模型")["id"]
    return Tenant(token, tenant, project, key["key"], key["id"], chat, embedding_model)


# ---------------------------------------------------------------------------------------------------------------------
# SSE 客户端

class SseClient(threading.Thread):
    """读一个事件流，记下每帧的 seq；连接断开（节点被杀）或收到终止条件时结束。"""

    def __init__(self, url: str, key: str, last_event_id: int | None = None, until=None):
        super().__init__(daemon=True)
        self.url, self.key, self.last_event_id, self.until = url, key, last_event_id, until
        self.frames: list[dict] = []
        self.error: Exception | None = None
        self.opened = threading.Event()

    def run(self):
        headers = {"Authorization": "Bearer " + self.key, "Accept": "text/event-stream"}
        if self.last_event_id is not None:
            headers["Last-Event-ID"] = str(self.last_event_id)
        try:
            with OPENER.open(urllib.request.Request(self.url, headers=headers), timeout=120) as response:
                self.opened.set()
                event, seq, data = None, None, []
                while True:
                    raw = response.readline()
                    if not raw:
                        return
                    line = raw.decode("utf-8").rstrip("\r\n")
                    if line == "":
                        # 只在流上的事件（event_start、event_delta、ping）没有 id，seq 为空
                        if event and data:
                            frame = {"event": event, "seq": seq, "data": json.loads("\n".join(data))}
                            self.frames.append(frame)
                            if self.until and self.until(frame):
                                return
                        event, seq, data = None, None, []
                    elif line.startswith("event:"):
                        event = line[6:].strip()
                    elif line.startswith("id:"):
                        seq = int(line[3:].strip())
                    elif line.startswith("data:"):
                        data.append(line[5:].lstrip())
        except Exception as e:  # noqa: BLE001 —— 节点被强杀时连接断开是预期的
            self.error = e
        finally:
            self.opened.set()


# ---------------------------------------------------------------------------------------------------------------------
# 本机替身：Webhook 接收方与 MCP 服务

def read_body(handler: BaseHTTPRequestHandler) -> bytes:
    """读请求体：定长或分块传输（runtime 的 Reactor Netty 发送器用分块）。"""
    if handler.headers.get("Transfer-Encoding", "").lower() == "chunked":
        body = b""
        while True:
            size = int(handler.rfile.readline().split(b";")[0].strip(), 16)
            if size == 0:
                handler.rfile.readline()
                return body
            body += handler.rfile.read(size)
            handler.rfile.readline()
    return handler.rfile.read(int(handler.headers.get("Content-Length", "0")))


class WebhookReceiver:
    """记录收到的 Webhook，并按 Meridian-Signature 验签（ADR-0049：HMAC-SHA256(密钥, "{t}.{请求体}")）。"""

    def __init__(self):
        self.secret: str | None = None
        self.received: list[dict] = []
        receiver = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):  # noqa: N802
                body = read_body(self)
                signature = dict(part.split("=", 1) for part in self.headers.get("Meridian-Signature", "").split(","))
                expected = hmac.new(receiver.secret.encode(), f"{signature.get('t')}.".encode() + body,
                                    hashlib.sha256).hexdigest() if receiver.secret else None
                receiver.received.append({"event": json.loads(body), "valid": hmac.compare_digest(
                    expected or "", signature.get("v1", "")), "delivery": self.headers.get("Meridian-Delivery-Id")})
                self.send_response(200)
                self.end_headers()

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.server.server_port}/hooks"

    def types(self) -> list[str]:
        return [r["event"]["type"] for r in self.received]

    def close(self):
        self.server.shutdown()


class McpStub:
    """最小的 MCP Streamable HTTP 服务（JSON 响应、不开 SSE 流）：一个有副作用的工具 create_ticket，校验 Bearer 令牌。"""

    def __init__(self):
        self.calls: list[dict] = []
        stub = self

        class Handler(BaseHTTPRequestHandler):
            def _reply(self, status: int, payload=None, headers: dict | None = None):
                body = b"" if payload is None else json.dumps(payload).encode()
                self.send_response(status)
                for name, value in (headers or {}).items():
                    self.send_header(name, value)
                if payload is not None:
                    self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self):  # noqa: N802 —— 不提供服务端推送流
                self._reply(405)

            def do_DELETE(self):  # noqa: N802
                self._reply(200)

            def do_POST(self):  # noqa: N802
                if self.headers.get("Authorization") != "Bearer " + MCP_TOKEN:
                    self._reply(401, {"error": "unauthorized"})
                    return
                message = json.loads(read_body(self))
                method, request_id = message.get("method"), message.get("id")
                if request_id is None:
                    self._reply(202)
                    return
                if method == "initialize":
                    result = {"protocolVersion": message["params"].get("protocolVersion", "2025-06-18"),
                              "capabilities": {"tools": {"listChanged": False}},
                              "serverInfo": {"name": "e2e-tickets", "version": "1.0.0"}}
                    self._reply(200, {"jsonrpc": "2.0", "id": request_id, "result": result},
                                {"Mcp-Session-Id": "e2e-" + uuid.uuid4().hex})
                elif method == "tools/list":
                    tool = {"name": "create_ticket", "description": "创建一张工单（有副作用）",
                            "inputSchema": {"type": "object", "properties": {"title": {"type": "string"}},
                                            "required": ["title"]}}
                    self._reply(200, {"jsonrpc": "2.0", "id": request_id, "result": {"tools": [tool]}})
                elif method == "tools/call":
                    stub.calls.append(message["params"])
                    result = {"content": [{"type": "text", "text": "工单 TCK-1001 已创建"}], "isError": False}
                    self._reply(200, {"jsonrpc": "2.0", "id": request_id, "result": result})
                elif method == "ping":
                    self._reply(200, {"jsonrpc": "2.0", "id": request_id, "result": {}})
                else:
                    self._reply(200, {"jsonrpc": "2.0", "id": request_id,
                                      "error": {"code": -32601, "message": "method not found"}})

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.server.server_port}/mcp"

    def close(self):
        self.server.shutdown()


# ---------------------------------------------------------------------------------------------------------------------
# 场景

def scenario_1_knowledge_acl(live: bool):
    """知识库问答：带权限标签的文档只对有对应标签的终端用户可见（REST 检索与智能体的 knowledge_search 两条路径）。"""
    t = new_tenant("knowledge", live=live, embedding=True)
    kb = ok(t.api("POST", f"{KNOWLEDGE}/v1/knowledge-bases",
                  {"name": "制度库", "embedding_model": t.embedding_model}), "建知识库")["id"]
    documents = {
        "finance.md": ("# 差旅住宿标准\n\n一线城市差旅住宿标准为每晚 680 元，其他城市每晚 450 元。\n", "dept:finance"),
        "hr.md": ("# 差旅住宿标准（人力内部）\n\n人力资源部门差旅住宿另行审批，标准编号 HR-STAY-77。\n", "dept:hr"),
        "public.md": ("# 年假\n\n员工每年享有 10 天带薪年假。\n", None),
    }
    ids = {}
    for name, (content, tag) in documents.items():
        body, content_type = multipart({"acl_tags": tag} if tag else {}, {"file": (name, content.encode())})
        ids[name] = ok(t.api("POST", f"{KNOWLEDGE}/v1/knowledge-bases/{kb}/documents", body,
                             {"Content-Type": content_type}), f"上传 {name}")["id"]
    deadline = time.monotonic() + 180
    while True:
        statuses = {name: ok(t.api("GET", f"{KNOWLEDGE}/v1/knowledge-bases/{kb}/documents/{doc}"), "查文档")["status"]
                    for name, doc in ids.items()}
        if all(s == "ready" for s in statuses.values()):
            break
        check("failed" not in statuses.values(), f"文档写入失败：{statuses}")
        check(time.monotonic() < deadline, f"文档在 180 秒内没有写完：{statuses}")
        time.sleep(1)
    log(f"PASS 场景 1：知识库 {kb} 写入 3 份文档（两份带权限标签）")

    def search(tags):
        hits = ok(t.api("POST", f"{KNOWLEDGE}/v1/knowledge-bases/{kb}/search",
                        {"query": "差旅住宿标准", "top_k": 10, "acl_tags": tags}), "检索")["data"]
        return {hit["filename"] for hit in hits}

    finance, hr = search(["dept:finance"]), search(["dept:hr"])
    check("finance.md" in finance and "hr.md" not in finance, f"财务标签的检索结果不对：{finance}")
    check("hr.md" in hr and "finance.md" not in hr, f"人力标签的检索结果不对：{hr}")
    check(not ({"finance.md", "hr.md"} & search([])), "没有标签时不应看到受限文档")
    log("PASS 场景 1：REST 检索按调用方的标签过滤（财务只见财务，人力只见人力，无标签只见公开）")

    environment = t.environment("问答")
    agent = t.agent("policy-qa", tools=["knowledge_search"], knowledge_base_ids=[kb],
                    rules=[{"tool_name": "knowledge_search", "behavior": "allow", "layer": "agent_version"}],
                    system_prompt="你是制度问答助手。回答前必须先用 knowledge_search 检索知识库，只依据检索结果回答。")
    if live:
        question = "差旅住宿标准是多少？"
    else:
        # knowledge_search 的入参必须给出知识库 ID（本次运行新建的），脚本按它生成，脚本化模型服务遇到新名字时重新读目录
        script = f"e2e-knowledge-{kb.lower()}"
        (RUN_SCRIPTS / f"{script}.yaml").write_text(
            f"name: {script}\n"
            "description: 场景 1：调用 knowledge_search 检索差旅住宿标准，再回答（m1_backend.py 按本次的知识库生成）。\n"
            "steps:\n"
            "  - tool_calls:\n"
            "      - name: knowledge_search\n"
            f"        arguments: {{query: 差旅住宿标准, knowledge_base_ids: [{kb}]}}\n"
            "  - text: 已按知识库的检索结果回答。\n", "utf-8")
        question = f"#script:{script} 差旅住宿标准是多少？"
    results = {}
    for user, tag in (("u_finance", "dept:finance"), ("u_hr", "dept:hr")):
        session = t.session(agent, environment, end_user_id=user, end_user_attributes={"acl_tags": [tag]})
        t.message(session, question)
        events = t.wait(session, idle(), f"场景 1 的 {user} 会话", timeout=180)
        calls = [e for e in of_type(events, "agent.tool_result") if e["data"]["name"] == "knowledge_search"]
        check(calls, f"{user} 的智能体没有调用 knowledge_search")
        results[user] = "".join(tool_result_text(e) for e in calls)
        answer = "".join(b.get("text", "") for e in of_type(events, "agent.message") for b in e["data"]["content"])
        check(answer.strip(), f"{user} 没有得到回答")
        if live:
            log(f"      {user} 的回答：{answer[:120]}")
    check("680" in results["u_finance"] and "HR-STAY-77" not in results["u_finance"], "财务用户检索到的内容不对")
    check("HR-STAY-77" in results["u_hr"] and "680" not in results["u_hr"], "人力用户检索到的内容不对")
    log("PASS 场景 1：智能体的 knowledge_search 按会话终端用户的标签过滤，两个用户看到各自部门的标准")


def scenario_2_docx_in_sandbox(live: bool):
    """文档编制：智能体在会话沙箱里用内置 docx 技能生成 Word，业务方从 outputs/ 下载。"""
    t = new_tenant("docx", live=live)
    files = []
    root = REPO / "skills" / "builtin" / "docx"
    for path in sorted(p for p in root.rglob("*") if p.is_file() and "__pycache__" not in p.parts):
        files.append({"path": path.relative_to(root).as_posix(), "content": path.read_text("utf-8")})
    skill = t.project_admin("POST", "/skills", {"files": files}, "上传 docx 技能")["id"]
    environment = t.environment("沙箱", tier="sandbox", size="small")
    allow = [{"tool_name": name, "behavior": "allow", "layer": "agent_version"}
             for name in ("write_file", "read_file", "list_files", "bash")]
    agent = t.agent("writer", tools=["write_file", "read_file", "list_files", "bash"], rules=allow,
                    skills=[{"skill_id": skill, "version": 1}],
                    system_prompt="你是文档助手。用户要 Word 文档时使用 docx 技能，产物放在 outputs/。")
    session = t.session(agent, environment, end_user_id="u_writer")
    if live:
        t.message(session, "请写一份三段的季度报告草稿（收入增长 12%、新客户 30 家、续约率 95%），做成 Word 文档，保存为 outputs/report.docx。")
    else:
        t.message(session, "#script:e2e-docx 把季度报告做成 Word")
    t.wait(session, idle(), "场景 2 的会话", timeout=420)
    listing = ok(t.api("GET", f"{RUNTIME_A}/v1/sessions/{session}/workspace:list?prefix=outputs/"), "列出工作区")["data"]
    docx = [f["path"] for f in listing if f["path"].endswith(".docx")]
    check(docx, f"outputs/ 下没有 Word 文档：{[f['path'] for f in listing]}")
    path = urllib.parse.quote(docx[0], safe="")
    content = t.api("GET", f"{RUNTIME_A}/v1/sessions/{session}/workspace/files/{path}")
    check(content.status == 200, f"下载 {docx[0]} 失败：HTTP {content.status}")
    with zipfile.ZipFile(io.BytesIO(content.body)) as archive:
        document = archive.read("word/document.xml").decode("utf-8")
    check("<w:p" in document, "下载的文件不是有效的 Word 文档")
    if not live:
        check("季度报告" in document and "1200" in document, "Word 文档里没有 Markdown 中的标题与表格")
    log(f"PASS 场景 2：会话 {session} 在沙箱中生成 {docx[0]}（{len(content.body)} 字节），下载后是有效的 Word 文档")
    ok(t.api("DELETE", f"{RUNTIME_A}/v1/sessions/{session}"), "删除会话（释放沙箱）")


def scenario_3_mcp_approval_via_webhook(live: bool):
    """审批：智能体调用标记为 ask 的 MCP 工具，业务方经 Webhook 收到请求，批准后智能体继续执行。"""
    t = new_tenant("approval", live=live)
    mcp, receiver = McpStub(), WebhookReceiver()
    try:
        secret = t.admin("POST", "/secrets", {"name": "工单服务令牌", "project_id": t.project,
                                               "value": "Bearer " + MCP_TOKEN}, "写入项目级凭据")["id"]
        server = t.project_admin("POST", "/mcp-servers", {
            "name": "tickets", "transport": "streamable_http", "url": mcp.url,
            "header_secret_ids": {"Authorization": secret}, "side_effect": "non_idempotent"}, "登记 MCP 服务")
        check([tool["name"] for tool in server["tools"]] == ["create_ticket"], f"探测到的工具不对：{server['tools']}")
        webhook = t.project_admin("POST", "/webhooks", {
            "url": receiver.url, "events": ["session.status_requires_action", "session.status_idle"]}, "建 Webhook")
        receiver.secret = webhook["signing_secret"]
        environment = t.environment("审批")
        agent = t.agent("ticketing", tools=[], mcp_servers=[{"mcp_server_id": server["id"], "enabled_tools": []}],
                        rules=[{"tool_name": "mcp__tickets__create_ticket", "behavior": "ask", "layer": "agent_version"}],
                        system_prompt="你是客服助手。用户要求退款时调用 create_ticket 创建工单。")
        session = t.session(agent, environment, end_user_id="u_refund")
        t.message(session, "订单 o-1001 要退款，请帮我建一张工单。" if live else "#script:e2e-mcp-ticket 订单 o-1001 要退款")
        events = t.wait(session, requires_action, "场景 3 等待审批", timeout=180)
        action = events[-1]["data"]["actions"][0]
        check(action["type"] == "tool_confirmation", f"待处理动作的类型不对：{action}")
        check(not mcp.calls, "批准之前 MCP 工具不应被调用")
        deadline = time.monotonic() + 60
        while "session.status_requires_action" not in receiver.types():
            check(time.monotonic() < deadline, "60 秒内没有收到 requires_action 的 Webhook")
            time.sleep(0.5)
        hook = next(r for r in receiver.received if r["event"]["type"] == "session.status_requires_action")
        check(hook["valid"], "Webhook 签名验证失败")
        check(hook["event"]["data"]["actions"][0]["id"] == action["id"], "Webhook 中的动作与会话中的不一致")
        log(f"PASS 场景 3：会话 {session} 停在审批，Webhook 收到 requires_action（签名有效），工具尚未执行")

        t.send(session, [{"type": "user.tool_confirmation", "action_id": action["id"], "approved": True}])
        events = t.wait(session, idle(after=events[-1]["seq"]), "场景 3 批准后续跑", timeout=180)
        check(len(mcp.calls) == 1 and mcp.calls[0]["name"] == "create_ticket", f"MCP 工具调用次数不对：{mcp.calls}")
        results = of_type(events, "agent.mcp_tool_result")
        check(results and "TCK-1001" in tool_result_text(results[-1]), "没有 MCP 工具的结果事件")
        deadline = time.monotonic() + 60
        while "session.status_idle" not in receiver.types():
            check(time.monotonic() < deadline, "60 秒内没有收到 idle 的 Webhook")
            time.sleep(0.5)
        deliveries = ok(t.api("GET", f"{RUNTIME_A}/v1/webhook-deliveries?session_id={session}"), "投递记录")["data"]
        check(deliveries and all(d["status"] == "succeeded" for d in deliveries), f"投递记录不对：{deliveries}")
        log(f"PASS 场景 3：批准后工具执行一次、智能体继续到 idle，{len(deliveries)} 条投递记录都成功")
    finally:
        mcp.close()
        receiver.close()


def scenario_4_custom_tool(live: bool):
    """自定义工具：业务方声明 query_order，智能体调用后业务方回传结果，会话继续。"""
    t = new_tenant("custom-tool")
    environment = t.environment("自定义工具")
    agent = t.agent("orders", tools=[])
    session = t.session(agent, environment, end_user_id="u_orders", custom_tools=[{
        "name": "query_order", "description": "按订单号查询订单状态", "side_effect": "none",
        "input_schema": {"type": "object", "properties": {"order_id": {"type": "string"}}, "required": ["order_id"]}}])
    t.message(session, "#script:e2e-query-order 订单 o-1001 到哪了")
    events = t.wait(session, requires_action, "场景 4 等待自定义工具", timeout=90)
    action = events[-1]["data"]["actions"][0]
    check(action["type"] == "custom_tool_result" and action["tool_name"] == "query_order", f"动作不对：{action}")
    use = of_type(events, "agent.custom_tool_use")
    check(use and use[-1]["data"]["input"] == {"order_id": "o-1001"}, f"自定义工具的入参不对：{use}")
    t.send(session, [{"type": "user.custom_tool_result", "action_id": action["id"],
                      "content": [{"type": "text", "text": "{\"status\": \"shipped\", \"carrier\": \"SF\"}"}]}])
    events = t.wait(session, idle("end_turn", after=events[-1]["seq"]), "场景 4 回传之后", timeout=90)
    answer = "".join(b.get("text", "") for e in of_type(events, "agent.message") for b in e["data"]["content"])
    check("已发货" in answer, f"回传结果之后没有继续回答：{answer}")
    log(f"PASS 场景 4：会话 {session} 调用 query_order、等待回传，回传后继续回答并结束")


def scenario_5_steer_and_interrupt(live: bool):
    """运行中发 steer，被注入当前轮；发 interrupt，当前轮在下一个检查点停止。"""
    t = new_tenant("steer")
    environment = t.environment("steer")
    agent = t.agent("steer", tools=["list_files"],
                    rules=[{"tool_name": "list_files", "behavior": "allow", "layer": "agent_version"}])
    session = t.session(agent, environment, end_user_id="u_steer")
    t.message(session, "#script:e2e-steer 先看看工作区")
    t.wait(session, lambda ev: any(e["type"] == "session.status_running" for e in ev), "场景 5 开始执行")
    sent = t.message(session, "补充一下：只看 docs 目录。", delivery="steer")
    steer_id = sent[0]["id"]
    events = t.wait(session, idle("end_turn"), "场景 5 steer 的这一轮", timeout=90)
    consumed = of_type(events, "session.message_consumed")
    check(consumed and steer_id in consumed[-1]["data"]["event_ids"], f"steer 没有被当前轮消费：{consumed}")
    turns = {e["turn_id"] for e in events if e["type"] == "session.status_running"}
    check(len(turns) == 1, f"steer 不应开启新的一轮：{turns}")
    check(consumed[-1]["turn_id"] in turns, "steer 不是在当前轮被注入的")
    log(f"PASS 场景 5：steer 在当前轮（{consumed[-1]['turn_id']}）被注入，没有另起一轮")

    last = events[-1]["seq"]
    started = time.monotonic()
    t.message(session, "#script:long-stream 讲一个很长的故事")
    sse = SseClient(f"{RUNTIME_A}/v1/sessions/{session}/stream?event_deltas=true", t.key, last,
                    until=lambda f: f["event"] == "event_delta")
    sse.start()
    sse.join(30)
    check(any(f["event"] == "event_delta" for f in sse.frames), "流上没有出现内容增量")
    t.send(session, [{"type": "user.interrupt"}])
    t.wait(session, idle("interrupted", after=last), "场景 5 中断", timeout=30)
    elapsed = time.monotonic() - started
    check(elapsed < 10, f"中断之后应提前结束（脚本完整输出约 11 秒），实际 {elapsed:.1f} 秒")
    log(f"PASS 场景 5：输出中途发 interrupt，本轮以 interrupted 结束（{elapsed:.1f} 秒，脚本完整输出约 11 秒）")


def metric(runtime: str, name: str) -> float:
    reply = http("GET", f"{runtime}/actuator/metrics/{name}")
    if reply.status != 200:
        return 0.0
    return next((m["value"] for m in reply.json()["measurements"] if m["statistic"] == "COUNT"), 0.0)


def env_sh(*args: str):
    subprocess.run([str(REPO / "e2e" / "backend" / "env.sh"), *args], check=True)


def scenario_6_node_crash(live: bool):
    """强杀执行中的 runtime 节点，会话在 45 秒内被其他节点续跑；SSE 客户端收到的事件序号连续、不重复。"""
    t = new_tenant("crash")
    environment = t.environment("续跑")
    agent = t.agent("crash", tools=[])
    session = t.session(agent, environment, end_user_id="u_crash")
    nodes = {"a": RUNTIME_A, "b": RUNTIME_B}
    claims = {node: metric(url, "meridian.turn.queue.wait") for node, url in nodes.items()}
    t.message(session, "#script:e2e-slow 慢慢讲")
    t.wait(session, lambda ev: any(e["type"] == "session.status_running" for e in ev), "场景 6 开始执行")
    holder = next((node for node, url in nodes.items() if metric(url, "meridian.turn.queue.wait") > claims[node]), None)
    check(holder is not None, "找不到认领本轮的节点（两个节点都要开放 /actuator/metrics）")
    survivor = "b" if holder == "a" else "a"
    first = SseClient(f"{nodes[holder]}/v1/sessions/{session}/stream", t.key, 0)
    first.start()
    first.opened.wait(10)
    time.sleep(3)
    env_sh("kill-runtime", holder)
    killed_at = time.monotonic()
    first.join(15)
    last_seen = max((f["seq"] for f in first.frames if f["seq"] is not None), default=0)
    log(f"      节点 {holder} 被强杀，第一条连接收到 seq ≤ {last_seen}，改连节点 {survivor} 续传")
    second = SseClient(f"{nodes[survivor]}/v1/sessions/{session}/stream", t.key, last_seen,
                       until=lambda f: f["event"] == "session.status_idle")
    second.start()
    try:
        events = t.wait(session, lambda ev: sum(e["type"] == "session.status_running" for e in ev) >= 2,
                        "场景 6 其他节点接管", timeout=60, runtime=nodes[survivor])
        resumed = time.monotonic() - killed_at
        check(resumed <= 45, f"接管用了 {resumed:.1f} 秒，超过 45 秒")
        log(f"PASS 场景 6：节点 {survivor} 在强杀后 {resumed:.1f} 秒接管本轮（不超过 45 秒）")
        t.wait(session, idle("end_turn"), "场景 6 续跑完成", timeout=90, runtime=nodes[survivor])
        second.join(30)
        seqs = [f["seq"] for f in first.frames + second.frames if f["seq"] is not None]
        check(len(seqs) == len(set(seqs)), f"SSE 收到重复的序号：{seqs}")
        check(seqs == list(range(1, len(seqs) + 1)), f"SSE 的序号不连续：{seqs}")
        stored = [e["seq"] for e in t.events(session, nodes[survivor])]
        check(seqs == stored, "SSE 收到的事件与库中的事件不一致")
        reschedule = of_type(t.events(session, nodes[survivor]), "session.status_rescheduled")
        check(reschedule and reschedule[-1]["data"]["reason"] == "lease_expired", f"没有 lease_expired 的置回：{reschedule}")
        log(f"PASS 场景 6：两段 SSE 共收到 {len(seqs)} 个事件，序号 1..{len(seqs)} 连续、不重复，与库中一致")
    finally:
        env_sh("start-runtime", holder)


def scenario_7_session_budget(live: bool):
    """会话预算耗尽时以 budget_exceeded 停止，账本与 Redis 计数一致。"""
    t = new_tenant("budget")
    environment = t.environment("预算")
    agent = t.agent("budget", tools=[])
    session = t.session(agent, environment, end_user_id="u_budget")
    t.message(session, "#script:chat 你好")
    events = t.wait(session, idle("end_turn"), "场景 7 第一轮")
    used = 0
    deadline = time.monotonic() + 60
    while used == 0:
        records = t.admin("GET", f"/usage-records?session_id={session}&kind=model_tokens&limit=100")["data"]
        used = sum(r["input_tokens"] + r["output_tokens"] for r in records)
        check(time.monotonic() < deadline, "60 秒内账本没有这一轮的用量")
        time.sleep(1)
    t.admin("POST", "/budgets", {"level": "session", "scope_id": session, "hard_limit_tokens": used + 5}, "设置会话预算")
    time.sleep(1)
    t.message(session, "#script:chat 再说一句")
    events = t.wait(session, idle("budget_exceeded", after=events[-1]["seq"]), "场景 7 预算耗尽", timeout=60)
    log(f"PASS 场景 7：会话已用 {used} token、硬阈值 {used + 5}，下一轮以 budget_exceeded 停止")
    time.sleep(3)
    records = t.admin("GET", f"/usage-records?session_id={session}&kind=model_tokens&limit=100")["data"]
    ledger = sum(r["input_tokens"] + r["output_tokens"] for r in records)
    month = time.strftime("%Y-%m", time.gmtime())
    counters = {
        "session": redis_get(f"budget:{{{t.tenant}}}:used:session:{session}:total"),
        "api_key": redis_get(f"budget:{{{t.tenant}}}:used:api_key:{t.key_id}:{month}"),
        "project": redis_get(f"budget:{{{t.tenant}}}:used:project:{t.project}:{month}"),
        "tenant": redis_get(f"budget:{{{t.tenant}}}:used:tenant:{t.tenant}:{month}"),
    }
    check(all(int(v or 0) == ledger for v in counters.values()), f"Redis 计数 {counters} 与账本 {ledger} 不一致")
    log(f"PASS 场景 7：账本 {ledger} token，四级 Redis 计数都等于账本（预扣已释放）")


def scenario_8_tenant_isolation(live: bool):
    """两个租户并发：一个租户打满并发上限，另一个租户的排队时间不受影响。"""
    noisy, quiet = new_tenant("noisy"), new_tenant("quiet")
    for tenant in (noisy, quiet):
        ok(http("PUT", f"{tenant.base}/quotas", tenant.token, {"max_concurrent_turns": 2, "event_retention_days": 30}),
           "设置并发配额")
    time.sleep(1)
    setups = {}
    for tenant in (noisy, quiet):
        setups[tenant.tenant] = (tenant.agent("busy", tools=[]), tenant.environment("并发"))
    noisy_sessions = [noisy.session(*setups[noisy.tenant], end_user_id=f"u_{i}") for i in range(6)]
    for session in noisy_sessions:
        noisy.message(session, "#script:e2e-busy 占用名额")
    time.sleep(2)
    quiet_sessions = [quiet.session(*setups[quiet.tenant], end_user_id=f"q_{i}") for i in range(2)]
    for session in quiet_sessions:
        quiet.message(session, "#script:chat 你好")

    def queue_waits(tenant: Tenant, sessions: list[str]) -> list[float]:
        waits = []
        for session in sessions:
            events = tenant.wait(session, idle("end_turn"), "场景 8 的会话", timeout=120)
            sent = next(e for e in events if e["type"] == "user.message")
            running = next(e for e in events if e["type"] == "session.status_running")
            waits.append(parse_time(running["created_at"]) - parse_time(sent["created_at"]))
        return waits

    quiet_waits = queue_waits(quiet, quiet_sessions)
    noisy_waits = queue_waits(noisy, noisy_sessions)
    check(max(quiet_waits) < 3, f"安静租户的排队时间受影响：{[round(w, 1) for w in quiet_waits]} 秒")
    check(max(noisy_waits) > 5, f"繁忙租户应该排队（并发上限 2、6 个会话）：{[round(w, 1) for w in noisy_waits]} 秒")
    log("PASS 场景 8：繁忙租户排队至多 %.1f 秒，安静租户排队至多 %.1f 秒（不受影响）"
        % (max(noisy_waits), max(quiet_waits)))


def parse_time(text: str) -> float:
    from datetime import datetime
    return datetime.fromisoformat(text.replace("Z", "+00:00")).timestamp()


SCENARIOS = {
    1: scenario_1_knowledge_acl,
    2: scenario_2_docx_in_sandbox,
    3: scenario_3_mcp_approval_via_webhook,
    4: scenario_4_custom_tool,
    5: scenario_5_steer_and_interrupt,
    6: scenario_6_node_crash,
    7: scenario_7_session_budget,
    8: scenario_8_tenant_isolation,
}

LIVE = {1, 2, 3}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--live", action="store_true", help="场景 1、2、3 用真实模型（DASHSCOPE_API_KEY）")
    parser.add_argument("--only", default="", help="只跑这些场景，逗号分隔")
    parser.add_argument("--skip", default="", help="跳过这些场景，逗号分隔")
    args = parser.parse_args()
    if args.live and not os.environ.get("DASHSCOPE_API_KEY"):
        log("FAIL --live 需要环境变量 DASHSCOPE_API_KEY")
        return 1
    selected = [int(n) for n in args.only.split(",") if n] or list(SCENARIOS)
    if args.live:
        selected = [n for n in selected if n in LIVE]
    selected = [n for n in selected if str(n) not in args.skip.split(",")]
    failures = []
    for number in selected:
        scenario = SCENARIOS[number]
        log(f"== 场景 {number}：{scenario.__doc__.strip().splitlines()[0]}")
        started = time.monotonic()
        try:
            scenario(args.live)
            log(f"   场景 {number} 通过（{time.monotonic() - started:.0f} 秒）")
        except Exception as e:  # noqa: BLE001
            failures.append(number)
            log(f"FAIL 场景 {number}：{e}")
            if not isinstance(e, Failure):
                traceback.print_exc()
    log("全部通过" if not failures else f"失败的场景：{failures}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
