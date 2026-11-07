---
paths:
  - "doc-parser/**"
  - "sandbox-runtime/**"
---

# Python 子项目规则（精要）

完整规范：`docs/engineering/coding-standards.md`「Python 子项目」。

- 用 uv 管理依赖与 Python 版本，锁文件提交；不用全局 pip。
- Ruff 格式化与检查，Pyright 或 mypy 类型检查；函数签名必须有类型标注。
- 模块与公开函数写中文 docstring；不写复述代码的注释。
- 测试用 pytest；检查命令接入 `scripts/dev/verify.sh`。
- 镜像以非 root 运行；`sandbox-runtime` 的接口格式以 AgentScope 2.0.4 Kubernetes 沙箱扩展的约定为准，逐字段对齐。
- 不在日志中输出凭据、请求中的敏感内容与用户文件内容。
