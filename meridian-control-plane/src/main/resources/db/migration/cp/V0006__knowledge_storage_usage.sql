-- 用量账本加入知识库存储量的种类（T18，ADR-0051）：knowledge 每天为每个有内容的知识库上报一条 knowledge_storage_gb_days。
-- 分区表上的检查约束随父表修改，作用于全部分区。

ALTER TABLE usage_records DROP CONSTRAINT usage_records_kind_check;
ALTER TABLE usage_records
    ADD CONSTRAINT usage_records_kind_check CHECK (kind IN ('model_tokens', 'embedding_tokens', 'rerank_tokens',
                                                            'sandbox_vcpu_seconds', 'search_calls',
                                                            'knowledge_storage_gb_days'));

COMMENT ON COLUMN usage_records.kind IS
    '计量种类：model_tokens、embedding_tokens、rerank_tokens、sandbox_vcpu_seconds、search_calls、knowledge_storage_gb_days';
