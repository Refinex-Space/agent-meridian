"""命令执行：``/bin/bash -c``、超时、输出上限，结束时整个进程组一起终止。"""

import contextlib
import os
import signal
import subprocess
import threading
from dataclasses import dataclass
from typing import IO

# 与 GNU coreutils 的 timeout 一致，不用 SIGKILL 的状态码，避免与 OOM 被杀混淆
TIMEOUT_EXIT_CODE = 124
# 进程组被终止后，等待输出读完的时间；逃出进程组（setsid）的子进程仍占着管道时不再等待
_DRAIN_GRACE_SECONDS = 1.0
_READ_CHUNK = 64 * 1024


@dataclass(frozen=True)
class ExecOutcome:
    """一条命令的执行结果。

    Attributes:
        stdout: 标准输出（UTF-8 解码，非法字节替换），超出上限时末尾带截断说明。
        stderr: 标准错误，规则同上；超时时末尾追加超时说明。
        exit_code: 退出码；超时为 124，被信号终止为 128 加信号值。
        timed_out: 是否因超时被终止。
    """

    stdout: str
    stderr: str
    exit_code: int
    timed_out: bool


class _CappedBuffer:
    """保留前 ``limit`` 个字节，其余只计数。"""

    def __init__(self, limit: int) -> None:
        self._limit = limit
        self._chunks: list[bytes] = []
        self._kept = 0
        self._omitted = 0
        self._lock = threading.Lock()

    def drain(self, stream: IO[bytes]) -> None:
        """读到 EOF 后关闭流；由读取线程独占这个流，主线程不关闭它。"""
        with stream:
            while chunk := os.read(stream.fileno(), _READ_CHUNK):
                self._feed(chunk)

    def _feed(self, chunk: bytes) -> None:
        with self._lock:
            room = self._limit - self._kept
            if room > 0:
                kept = chunk[:room]
                self._chunks.append(kept)
                self._kept += len(kept)
            self._omitted += max(0, len(chunk) - max(room, 0))

    def text(self) -> str:
        """返回已保留的内容，被截断时追加说明。"""
        with self._lock:
            data = b"".join(self._chunks).decode("utf-8", errors="replace")
            if self._omitted:
                data += f"\n[output truncated: {self._omitted} bytes omitted]"
            return data


def run_command(command: str, *, cwd: str, timeout: float, output_limit: int) -> ExecOutcome:
    """在新的进程组中执行一条 shell 命令。

    命令结束（或超时）后向整个进程组发送 SIGKILL：命令留下的后台进程不会继续运行，
    也不会占着输出管道。
    发送信号时组长进程尚未被回收，组 ID 不会被并发启动的其他命令复用。

    Args:
        command: 交给 ``/bin/bash -c`` 的命令。
        cwd: 工作目录。
        timeout: 最长执行秒数。
        output_limit: stdout、stderr 各自保留的最大字节数。

    Returns:
        执行结果。
    """
    process = subprocess.Popen(
        ["/bin/bash", "-c", command],
        cwd=cwd,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        start_new_session=True,
    )
    assert process.stdout is not None and process.stderr is not None
    stdout, stderr = _CappedBuffer(output_limit), _CappedBuffer(output_limit)
    readers = [
        threading.Thread(target=stdout.drain, args=(process.stdout,), daemon=True),
        threading.Thread(target=stderr.drain, args=(process.stderr,), daemon=True),
    ]
    for reader in readers:
        reader.start()

    exited = threading.Event()

    def watch() -> None:
        # WNOWAIT：只等待退出、不回收，组长保持僵尸状态直到下面的 wait()
        try:
            os.waitid(os.P_PID, process.pid, os.WEXITED | os.WNOWAIT)
        finally:
            exited.set()

    threading.Thread(target=watch, daemon=True).start()
    timed_out = not exited.wait(timeout)
    _kill_group(process.pid)
    # 超时时组长刚收到 SIGKILL：等观察线程确认它已退出再回收，
    # 否则观察线程的 waitid 会因子进程已被回收而失败
    exited.wait()
    returncode = process.wait()
    for reader in readers:
        reader.join(_DRAIN_GRACE_SECONDS)

    err_text = stderr.text()
    if timed_out:
        separator = "\n" if err_text and not err_text.endswith("\n") else ""
        err_text += f"{separator}Command timed out after {timeout:g} seconds"
        exit_code = TIMEOUT_EXIT_CODE
    else:
        exit_code = returncode if returncode >= 0 else 128 - returncode
    return ExecOutcome(stdout.text(), err_text, exit_code, timed_out)


def _kill_group(pgid: int) -> None:
    # 组里只剩已退出的组长时，Linux 返回成功，macOS 可能返回 EPERM
    with contextlib.suppress(ProcessLookupError, PermissionError):
        os.killpg(pgid, signal.SIGKILL)
