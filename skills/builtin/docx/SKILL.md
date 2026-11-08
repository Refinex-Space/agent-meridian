---
name: docx
description: Turn Markdown into a Word (.docx) document saved under outputs/ so the user can download it. Needs the bash tool (sandbox environments).
---
# Markdown to Word

Use this skill when the user asks for a Word document, a .docx file, or a downloadable report.

1. Write the content as Markdown to a file in the workspace, for example `drafts/report.md`
   (use write_file). Supported Markdown: headings (`#` to `######`), paragraphs, `**bold**`,
   `*italic*`, `` `code` ``, bullet lists (`-`, `*`), numbered lists (`1.`), fenced code blocks,
   pipe tables, and `---` horizontal rules.
2. Convert it with bash:

   ```bash
   python3 /workspace/.meridian/skills/docx/scripts/md_to_docx.py drafts/report.md outputs/report.docx --title "Report title"
   ```

   - The output must be under `outputs/`; files there are copied to platform storage when the turn ends.
   - `--title` sets the document title property and is optional.
   - The script prints the output path and the number of paragraphs and tables it wrote.
3. Tell the user the file name under `outputs/`. Do not paste the whole document back into the chat.

If the script reports an error, fix the Markdown file and run it again. Chinese text is supported.
