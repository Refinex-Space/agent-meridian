"""执行服务的配置，全部来自环境变量，由沙箱模板（Helm）设置。"""

import math
import os
from dataclasses import dataclass

DEFAULT_BASE_DIR = "/workspace"
DEFAULT_EXEC_TIMEOUT_SECONDS = 600.0
DEFAULT_OUTPUT_LIMIT_BYTES = 1024 * 1024


@dataclass(frozen=True)
class Settings:
    """执行服务配置。

    Attributes:
        base_dir: 工作区根目录，命令的工作目录，文件接口的路径都相对它解析。
        exec_timeout_seconds: 单条命令的最长执行时间，请求只能缩短不能延长。
        output_limit_bytes: stdout、stderr 各自保留的最大字节数，超出部分丢弃并标注。
    """

    base_dir: str
    exec_timeout_seconds: float
    output_limit_bytes: int


def load_settings() -> Settings:
    """从环境变量读取配置，非法值回退为默认值。

    Returns:
        当前进程的配置。
    """
    return Settings(
        base_dir=os.environ.get("SANDBOX_BASE_DIR", "").strip() or DEFAULT_BASE_DIR,
        exec_timeout_seconds=_positive_float(
            "SANDBOX_EXEC_TIMEOUT_SECONDS", DEFAULT_EXEC_TIMEOUT_SECONDS
        ),
        output_limit_bytes=int(
            _positive_float("SANDBOX_EXEC_OUTPUT_LIMIT_BYTES", DEFAULT_OUTPUT_LIMIT_BYTES)
        ),
    )


def _positive_float(name: str, default: float) -> float:
    try:
        value = float(os.environ.get(name, str(default)))
    except ValueError:
        return default
    return value if math.isfinite(value) and value > 0 else default
