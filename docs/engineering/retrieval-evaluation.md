# 检索评测记录

知识库检索（T19）的质量基线：四种检索方式在固定评测集上的命中率@5 与 MRR。离线回归 `RetrievalEvalIT` 以本页的实测值为下限，低于它时测试失败。

## 评测集

位置：`meridian-knowledge/src/test/resources/retrieval-eval/`。写定后不为改善指标而修改语料与问题。

- 语料（`corpus/`）：6 份自编的中文制度，每份 5 条条款，共 30 条，每条一个切片（Markdown 的二级标题切分）。

  | 文件 | 内容 | `acl_tags` |
  |---|---|---|
  | `travel-policy.md` | 差旅管理办法 | 无 |
  | `reimbursement.md` | 费用报销规定 | `dept:finance` |
  | `procurement.md` | 采购管理制度 | `dept:procurement` |
  | `information-security.md` | 信息安全管理规定 | `level:internal` |
  | `attendance.md` | 考勤与休假制度 | 无 |
  | `compensation.md` | 薪酬与绩效管理办法 | `level:confidential,role:hr` |

- 问题（`eval-set.json`）：20 个员工口吻的问题，多数换了说法（"餐费补贴"对"伙食补助"、"请客户吃饭"对"业务招待"），少数含数字与专有词（"12 年""Wi-Fi"）。每个问题的标准答案是一条条款，以文件名加条款标题标识。
- 调用方带全部四个部门与密级标签，30 个切片都可见；权限过滤由 `KnowledgeSearchIT`、`KnowledgeMcpIT`、`KnowledgeSessionIT` 单独验证。

## 指标

- 每个问题检索前 10 个切片。命中率@5：标准答案出现在前 5 的问题比例。MRR：标准答案排名倒数的平均，前 10 中没有时计 0。
- 四种方式（`SearchPlan`）：仅向量（HNSW 前 50）、仅全文（zhparser 词项"或"连接、`ts_rank_cd` 前 50）、混合（两路 RRF，k = 60）、混合加 rerank（RRF 的前 20 个候选经 rerank 取前 k）。检索参数取 `meridian.knowledge.search` 的默认值。

## 结果

2026-11-12，Embedding `qwen3.7-text-embedding`（1024 维），rerank `qwen3.7-text-rerank`，DashScope。

| 方式 | 命中率@5 | MRR | 每题排名（Q01–Q20） |
|---|---|---|---|
| 仅向量 | 1.00 | 0.9750 | 1 1 1 1 1 1 1 1 1 1 1 1 1 1 2 1 1 1 1 1 |
| 仅全文 | 0.95 | 0.7250 | 3 2 1 3 1 1 2 0 2 1 1 1 1 2 2 1 1 1 3 1 |
| 混合 | 1.00 | 0.9250 | 1 1 1 2 1 1 1 1 1 1 1 1 1 1 2 1 1 1 2 1 |
| 混合加 rerank | 1.00 | 1.0000 | 全部为 1 |

## 分析

- 混合加 rerank 最好，20 个问题的标准答案都排第一，生产的检索流程（`SearchPlan.STANDARD`）用它。
- 仅全文最弱：Q08"请客户吃饭人均可以花多少？"与"业务招待"条款没有共同的词，召回不到；换了说法的问题多排在第 2、3 位。
- 混合（不重排）的 MRR 低于仅向量：Q04、Q19 在仅向量时排第 1，混合后排第 2。这两题在仅全文中都排第 3，RRF 把向量第 1、全文第 3 的标准答案与另一个两路都较靠前的切片加总后排在了它之后。语料小、向量已经很准时，RRF 的作用是扩大召回（补上向量漏掉的字面匹配），排序交给 rerank；知识库没有设置 `rerank_model` 时退化为这一行的质量。
- 评测集小、指标接近上限，它守的是回归而不是区分度：任何让"混合加 rerank"不再全部第一的改动都会让 `RetrievalEvalIT` 失败。扩大评测集（更长的文档、表格、PDF 页码）留给后续任务。

## 录制与回归

- 录制在 `retrieval-eval/recording/`：`embeddings.json`（50 段文本的向量）、`reranks.json`（20 个问题各一次 rerank 的候选与得分）。
- 重新录制（改了语料、问题、切分或 Embedding 的输入格式之后）：

  ```bash
  ./mvnw -Plive -pl meridian-knowledge -am verify -Dit.test=RetrievalEvalEmbeddingsLiveIT -Dfailsafe.failIfNoSpecifiedTests=false   # 约 7 次调用
  ./mvnw -Plive -pl meridian-knowledge -am verify -Dit.test=RetrievalEvalRerankLiveIT -Dfailsafe.failIfNoSpecifiedTests=false       # 20 次调用
  ```

  第二步回放第一步录制的向量，输出四种方式的指标；新的数值写回本页与 `RetrievalEvalIT.BASELINE`。分两个类是为了每个类的真实调用不超过 20 次。
- 离线回归：`RetrievalEvalIT` 在普通的集成测试中运行，回放两份录制，结果是确定的（向量距离没有并列；全文同分时按切片 ID，语料按固定顺序逐份写入，ID 次序固定；RRF 同分时按首次出现的顺序）。
