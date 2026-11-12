/**
 * 写入的领域规则，纯 Java：解析结果的中间表示（{@link cn.refinex.meridian.knowledge.ingestion.domain.Block}）、切分
 * （{@link cn.refinex.meridian.knowledge.ingestion.domain.Chunker}）、Markdown 与纯文本的结构解析、写入作业的状态与重试计划、失败的分类
 * （架构设计「写入流程」，ADR-0051）。切分器只依赖中间表示，不依赖具体的解析器。
 */
package cn.refinex.meridian.knowledge.ingestion.domain;
