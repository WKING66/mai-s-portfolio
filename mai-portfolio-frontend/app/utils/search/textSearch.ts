export interface TextMatch {
  from: number
  to: number
}

/** 精确、非重叠的字面文本匹配；不把用户输入解释为正则或 Markdown。 */
export function findTextMatches(text: string, query: string): TextMatch[] {
  if (!query || /[\r\n]/.test(query)) {
    return []
  }
  const matches: TextMatch[] = []
  let offset = 0
  while (offset <= text.length - query.length) {
    const from = text.indexOf(query, offset)
    if (from < 0) {
      break
    }
    matches.push({ from, to: from + query.length })
    offset = from + query.length
  }
  return matches
}

export function nextTextMatch(index: number, count: number, direction: -1 | 1) {
  if (!count) {
    return -1
  }
  if (index < 0) {
    return direction === 1 ? 0 : count - 1
  }
  return (index + direction + count) % count
}

/** 面板只组织查找交互；读取、定位和写回由各自文档实现持有。 */
export interface DocumentSearchAdapter {
  find(query: string): number
  activate(index: number, locate: boolean): void | Promise<void>
  observe(changed: () => void): () => void
  clear(): void
  focus?(index: number): void
  history?(
    direction: 'undo' | 'redo',
    query: string,
  ): { next: number; total: number; locate: boolean } | null
  replace?(
    index: number,
    value: string,
    all: boolean,
  ): { replaced: number; next: number; total: number }
}
