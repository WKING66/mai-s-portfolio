const metrics = [
  'font-family',
  'font-size',
  'font-weight',
  'font-style',
  'line-height',
  'font-variant-ligatures',
  'font-kerning',
  'letter-spacing',
  'word-spacing',
  'padding-left',
  'padding-right',
  'padding-top',
  'padding-bottom',
  'tab-size',
  'direction',
]
interface SourceMeasure {
  value: string
  signature: string
  mirror: HTMLDivElement
  text: Text
  from: number
  to: number
  offset: number
}
const mirrors = new WeakMap<HTMLTextAreaElement, SourceMeasure>()

export function releaseImmersiveSourceMeasure(input: HTMLTextAreaElement | null) {
  if (!input) {
    return
  }
  mirrors.get(input)?.mirror.remove()
  mirrors.delete(input)
}

/** 只读原生软换行几何；同一次导航复用同一镜像，不按固定行高或百分比猜位置。 */
export function measureImmersiveSourceCaret(input: HTMLTextAreaElement, requested: number) {
  const position = Math.max(0, Math.min(requested, input.value.length))
  const styles = getComputedStyle(input)
  const width = input.clientWidth
  const signature = [width, ...metrics.map((property) => styles.getPropertyValue(property))].join(
    '|',
  )
  let cached = mirrors.get(input)
  if (cached?.value !== input.value || cached.signature !== signature) {
    releaseImmersiveSourceMeasure(input)
    cached = undefined
  }
  if (!cached || position < cached.from || position > cached.to) {
    releaseImmersiveSourceMeasure(input)
    // 硬换行分隔独立行布局。靠近末尾时从实际 scrollHeight 减去后缀高度，
    // 只排版当前行至末尾，避免为末段光标反复排版前面的数万字符。
    const suffix = position > input.value.length / 2 && input.scrollHeight > input.clientHeight + 1
    const from = suffix ? input.value.lastIndexOf('\n', Math.max(0, position - 1)) + 1 : 0
    const newline = input.value.indexOf('\n', position)
    const to = suffix || newline < 0 ? input.value.length : newline
    const mirror = document.createElement('div')
    for (const property of metrics) {
      mirror.style.setProperty(property, styles.getPropertyValue(property))
    }
    Object.assign(mirror.style, {
      position: 'fixed',
      top: '0',
      left: '-100000px',
      visibility: 'hidden',
      pointerEvents: 'none',
      width: width + 'px',
      boxSizing: 'border-box',
      whiteSpace: 'pre-wrap',
      overflowWrap: 'anywhere',
      wordBreak: 'break-word',
      contain: 'layout style paint',
    })
    // 尾部占位保留 textarea 最后一条空行；Range 不向正文中插入测量字符。
    const text = document.createTextNode(input.value.slice(from, to) + '\u200b')
    mirror.append(text)
    document.body.append(mirror)
    cached = {
      value: input.value,
      signature,
      mirror,
      text,
      from,
      to,
      offset: suffix ? input.scrollHeight - mirror.getBoundingClientRect().height : 0,
    }
    mirrors.set(input, cached)
  }
  const range = document.createRange()
  range.setStart(cached.text, position - cached.from)
  range.collapse(true)
  const result =
    cached.offset + range.getBoundingClientRect().top - cached.mirror.getBoundingClientRect().top
  return result
}
