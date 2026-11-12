package cn.refinex.meridian.runtime.workspace.domain;

import cn.refinex.meridian.common.error.ErrorCode;

/** 工作区 API 的错误码（ADR-0050），码值只增不改。 */
public enum WorkspaceErrorCode implements ErrorCode {
    /** 路径不合法：为空、以 {@code /} 开头、含 {@code .}/{@code ..}/空段、反斜杠或控制字符。 */
    INVALID_PATH("workspace.invalid_path", 400, "工作区路径不合法"),
    /** 文件不存在；{@code sandbox} 档还包括 {@code outputs/} 之外的路径。 */
    FILE_NOT_FOUND("workspace.file_not_found", 404, "工作区文件不存在"),
    /** 受保护路径由平台按智能体配置生成，只读。 */
    PROTECTED_PATH("workspace.protected_path", 403, "受保护路径只读"),
    /** {@code sandbox} 档的工作区在沙箱中，经 API 只读 {@code outputs/} 的副本。 */
    READ_ONLY("workspace.read_only", 409, "沙箱档的工作区经 API 只读");

    private final String code;
    private final int status;
    private final String title;

    WorkspaceErrorCode(String code, int status, String title) {
        this.code = code;
        this.status = status;
        this.title = title;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public String title() {
        return title;
    }
}
