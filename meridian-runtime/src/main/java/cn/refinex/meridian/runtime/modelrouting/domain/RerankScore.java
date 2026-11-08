package cn.refinex.meridian.runtime.modelrouting.domain;

/**
 * rerank 中一条候选文本的得分。
 *
 * @param index 候选文本在请求中的序号，从 0 开始
 * @param score 相关度，越大越相关，取供应商的值
 */
public record RerankScore(int index, double score) {}
