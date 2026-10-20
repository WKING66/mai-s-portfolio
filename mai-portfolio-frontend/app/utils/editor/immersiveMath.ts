/** 复用已有数学分隔符，不引入新的持久化语法。 */
export function immersiveMathSource(original: string, expression: string, block: boolean): string {
  let delimiters = block ? ['$$', '$$'] : ['$', '$']
  if (original.startsWith('\\[')) {
    delimiters = ['\\[', '\\]']
  } else if (original.startsWith('\\(')) {
    delimiters = ['\\(', '\\)']
  }
  // 行内至少保留一个空白；现有编辑器与阅读解析器均支持这一表示。
  let content = expression
  if (block) {
    content = '\n' + expression + '\n'
  } else if (!expression.trim()) {
    content = ' '
  }
  return delimiters[0]! + content + delimiters[1]!
}
