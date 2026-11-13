/**
 * 从文件选择框与拖放中读出技能包的文件（相对路径与字节）。选择文件夹时浏览器给出 `webkitRelativePath`；拖放文件夹时按
 * 文件系统条目逐层读出。只在浏览器端读取，不经过任何存储。
 */
import type { PickedFile } from './skill-package';

/** 文件选择框（`multiple` 或 `webkitdirectory`）选中的文件。 */
export async function readFileList(list: FileList | readonly File[]): Promise<PickedFile[]> {
  return Promise.all(
    Array.from(list).map(async (file) => ({
      path: file.webkitRelativePath || file.name,
      bytes: new Uint8Array(await file.arrayBuffer()),
    })),
  );
}

function fileOf(entry: FileSystemFileEntry): Promise<File> {
  return new Promise((resolve, reject) => entry.file(resolve, reject));
}

/** 读出一个目录下的全部条目：`readEntries` 每次只返回一批，读到空批为止。 */
function entriesOf(dir: FileSystemDirectoryEntry): Promise<FileSystemEntry[]> {
  const reader = dir.createReader();
  const all: FileSystemEntry[] = [];
  return new Promise((resolve, reject) => {
    const next = () =>
      reader.readEntries((batch) => {
        if (batch.length === 0) resolve(all);
        else {
          all.push(...batch);
          next();
        }
      }, reject);
    next();
  });
}

async function walk(entry: FileSystemEntry, out: PickedFile[]): Promise<void> {
  if (entry.isFile) {
    const file = await fileOf(entry as FileSystemFileEntry);
    // fullPath 以 / 开头（"/docx/SKILL.md"）。
    out.push({ path: entry.fullPath.replace(/^\/+/, ''), bytes: new Uint8Array(await file.arrayBuffer()) });
  } else if (entry.isDirectory) {
    for (const child of await entriesOf(entry as FileSystemDirectoryEntry)) await walk(child, out);
  }
}

/** 拖放进来的文件与文件夹；浏览器不支持条目接口时退回文件列表。 */
export async function readDataTransfer(data: DataTransfer): Promise<PickedFile[]> {
  const entries = Array.from(data.items)
    .map((item) => (item.kind === 'file' ? item.webkitGetAsEntry() : null))
    .filter((e): e is FileSystemEntry => e !== null);
  if (entries.length === 0) return readFileList(data.files);
  const out: PickedFile[] = [];
  for (const entry of entries) await walk(entry, out);
  return out;
}
