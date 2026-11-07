package cn.refinex.meridian.persistence.idempotency;

/**
 * 幂等执行的结果。
 *
 * @param response 响应：首次执行时是本次的结果，重放时是首次保存的结果
 * @param replayed 是否为重放；HTTP 层可据此加上响应头，告知调用方这是重放
 */
public record IdempotentResult(StoredResponse response, boolean replayed) {}
