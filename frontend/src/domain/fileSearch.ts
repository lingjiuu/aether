import { readdirSync } from 'node:fs';
import path from 'node:path';

const IGNORED_DIRS = new Set(['.git', 'node_modules', 'target', 'dist', 'build', '.idea', '.vscode']);
const IMAGE_EXTENSIONS = new Set(['.gif', '.jpg', '.jpeg', '.png', '.webp']);
const MAX_SCANNED_ENTRIES = 1200;

export type FileSuggestion = {
  path: string;
  displayPath: string;
  isImage: boolean;
};

export function searchFiles(cwd: string | undefined, query: string, limit = 12): FileSuggestion[] {
  const root = cwd?.trim();
  if (!root) {
    return [];
  }

  const normalizedQuery = query.trim().toLowerCase();
  const results: FileSuggestion[] = [];
  const dirs = [root];
  let scanned = 0;

  while (dirs.length && results.length < limit && scanned < MAX_SCANNED_ENTRIES) {
    const dir = dirs.shift() ?? root;
    let entries;
    try {
      entries = readdirSync(dir, { withFileTypes: true });
    } catch {
      continue;
    }

    for (const entry of entries.sort((left, right) => left.name.localeCompare(right.name))) {
      scanned += 1;
      if (scanned > MAX_SCANNED_ENTRIES || results.length >= limit) {
        break;
      }
      if (entry.name === '.DS_Store') {
        continue;
      }

      const absolutePath = path.join(dir, entry.name);
      const relativePath = path.relative(root, absolutePath);
      if (entry.isDirectory()) {
        if (!IGNORED_DIRS.has(entry.name)) {
          dirs.push(absolutePath);
        }
        continue;
      }
      if (!entry.isFile()) {
        continue;
      }
      if (normalizedQuery && !relativePath.toLowerCase().includes(normalizedQuery)) {
        continue;
      }
      results.push({
        path: absolutePath,
        displayPath: toPosix(relativePath),
        isImage: IMAGE_EXTENSIONS.has(path.extname(entry.name).toLowerCase()),
      });
    }
  }

  return results;
}

function toPosix(value: string): string {
  return value.split(path.sep).join('/');
}
