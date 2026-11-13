-- 知识库的 rerank 模型（T19，架构设计「检索流程」第 4 步）：检索时用它对融合后的候选重排，为空表示不重排。
ALTER TABLE knowledge_bases ADD COLUMN rerank_model text;

COMMENT ON COLUMN knowledge_bases.rerank_model IS '检索时重排用的 rerank 模型（租户目录中的 mdl_…）；为空表示只按 RRF 融合排名返回';
