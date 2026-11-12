# 写入测试语料

四份语料的内容都为本项目自拟，不来自任何有版权的文档（T18，ADR-0051）：

| 文件 | 内容 | 覆盖 |
|---|---|---|
| `travel-policy.docx` | 差旅费管理办法：标题样式的章、节两级标题，条款编号，一张住宿标准表 | Tika（POI）的标题层级、表格、长节切成多片 |
| `equipment-rules.pdf` | 办公设备采购与领用规定：两页，框线表格 | PDFBox 检查出框线网格，交给 doc-parser；表格整体成片、页码 |
| `scanned-notice.pdf` | 关于调整夏季作息时间的通知：只有图像（文字画在图像上，加了轻微模糊与倾斜） | 扫描件判定与 doc-parser 的 OCR |
| `onboarding.md` | 新员工入职指南：三级标题、有序与无序列表、表格、代码块 | Markdown 的结构解析 |
| `reimbursement-guide.pdf` | 费用报销操作指引：两页，只有文字（标题字号大于正文） | PDFBox 抽取文字、按字号推断标题、页码 |

PDF 由 fpdf2 用思源黑体（Noto Sans SC，SIL OFL 1.1）生成并嵌入字体子集，docx 由 python-docx 生成。
