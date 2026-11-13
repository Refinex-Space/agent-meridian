-- 用量账本加入知识检索次数（T19，登记事项 D70）：knowledge 每次检索（公开 API、MCP、智能体）上报一条 knowledge_search_calls，
-- 只进账本、不占预算。分区表上的检查约束随父表修改，作用于全部分区。

ALTER TABLE usage_records DROP CONSTRAINT usage_records_kind_check;
ALTER TABLE usage_records
    ADD CONSTRAINT usage_records_kind_check CHECK (kind IN ('model_tokens', 'embedding_tokens', 'rerank_tokens',
                                                            'sandbox_vcpu_seconds', 'search_calls',
                                                            'knowledge_storage_gb_days', 'knowledge_search_calls'));

COMMENT ON COLUMN usage_records.kind IS
    '计量种类：model_tokens、embedding_tokens、rerank_tokens、sandbox_vcpu_seconds、search_calls、knowledge_storage_gb_days、'
    'knowledge_search_calls';
