export interface SourceIndentEdit {
  from: number
  to: number
  text: string
  anchor: number
  head: number
}
/** 两空格缩进与项目风格一致；选区结束在下一行起点时不误改下一行。 */
export function indentMarkdownSource(
  value: string,
  start: number,
  end: number,
  outdent: boolean,
): SourceIndentEdit {
  if (start === end && !outdent) {
    return { from: start, to: end, text: '  ', anchor: start + 2, head: end + 2 }
  }
  const from = value.lastIndexOf('\n', start - 1) + 1
  const last = end > start && value[end - 1] === '\n' ? end - 1 : end
  const nextBreak = value.indexOf('\n', last)
  const to = nextBreak < 0 ? value.length : nextBreak
  const lines = value.slice(from, to).split('\n')
  let offset = from
  let startDelta = 0
  let endDelta = 0
  const text = lines
    .map((line) => {
      const removed = outdent ? (line.match(/^(?:\t| {1,2})/)?.[0].length ?? 0) : 0
      if (offset <= start) {
        startDelta += outdent ? -Math.min(removed, start - offset) : 2
      }
      if (offset < end || start === end) {
        endDelta += outdent ? -Math.min(removed, Math.max(0, end - offset)) : 2
      }
      offset += line.length + 1
      return outdent ? line.slice(removed) : '  ' + line
    })
    .join('\n')
  return { from, to, text, anchor: start + startDelta, head: end + endDelta }
}
