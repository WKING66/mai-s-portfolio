import type { Editor } from '@tiptap/core'
import type { Transaction } from '@tiptap/pm/state'
import type { EditorView } from '@tiptap/pm/view'

// Sampling is opt-in and local. Never collect content, key values or network data.
export function startEditorPerformance(editor: Editor) {
  type Sample = {
    time: number
    target: string
    kind: string
    queued?: number
    beforeInput?: number
    input?: number
    settled?: number
    frame?: number
    visible?: number
    transactions: { apply?: number; view: number; start: number; doc: boolean; steps: number }[]
  }
  const view = editor.view
  const root = view.dom
  const pane = root.closest('.canvas-pane')
  let recording = true
  const started = performance.now()
  const samples: Sample[] = []
  const frames: unknown[] = []
  const actions: { time: number; kind: string }[] = []
  let active:
    { start: number; sample: Sample; dispatchStart?: number; viewStart?: number } | undefined
  const exports = { json: 0, markdown: 0, text: 0, milliseconds: 0 }
  function target(event: Event) {
    if (!(event.target instanceof Element)) {
      return
    }
    if (event.target.closest('.math-editor')) {
      return 'formula'
    }
    if (!root.contains(event.target) || event.target.closest('[data-editor-controls]')) {
      return
    }
    return event.target.closest('[data-canvas-code-input]') ? 'code' : 'body'
  }
  function sample(event: Event, kind: string) {
    const area = target(event)
    if (!recording || !area || samples.length >= 120) {
      return
    }
    const now = performance.now()
    const queued = now - event.timeStamp
    const current = {
      start: now,
      sample: {
        time: Math.round(now - started),
        target: area,
        kind,
        ...(queued >= 0 && queued < 10000 ? { queued: Math.round(queued * 10) / 10 } : {}),
        transactions: [],
      } as Sample,
    }
    active = current
    samples.push(current.sample)
    setTimeout(() => (current.sample.settled = Math.round(performance.now() - current.start)), 0)
    requestAnimationFrame(() => {
      current.sample.frame = Math.round(performance.now() - current.start)
      if (active === current) {
        active = undefined
      }
      requestAnimationFrame(
        () => (current.sample.visible = Math.round(performance.now() - current.start)),
      )
    })
    return current
  }
  function key(event: KeyboardEvent) {
    if (event.ctrlKey || event.metaKey || event.altKey) {
      return
    }
    if (event.key.length === 1 || event.isComposing || event.key === 'Process') {
      sample(event, event.isComposing || event.key === 'Process' ? 'composition' : 'character')
    }
  }
  function beforeInput(event: InputEvent) {
    if (!target(event)) {
      return
    }
    const current = active ?? sample(event, event.inputType)
    if (current) {
      current.sample.kind = event.inputType
      current.sample.beforeInput = Math.round(performance.now() - current.start)
    }
  }
  function input(event: Event) {
    if (target(event) && active) {
      active.sample.input = Math.round(performance.now() - active.start)
    }
  }
  function beforeTransaction() {
    if (active) {
      active.viewStart = performance.now()
    }
  }
  function transaction({ transaction }: { transaction: Transaction }) {
    if (active?.viewStart !== undefined && active.sample.transactions.length < 12) {
      active.sample.transactions.push({
        ...(active.dispatchStart === undefined
          ? {}
          : {
              apply: Math.round((active.viewStart - active.dispatchStart) * 10) / 10,
            }),
        view: Math.round((performance.now() - active.viewStart) * 10) / 10,
        start: Math.round((active.viewStart - active.start) * 10) / 10,
        doc: transaction.docChanged,
        steps: transaction.steps.length,
      })
    }
  }
  let lastScroll = 0
  function scroll() {
    if (recording && performance.now() - lastScroll > 100 && actions.length < 80) {
      lastScroll = performance.now()
      actions.push({ time: Math.round(lastScroll - started), kind: 'scroll' })
    }
  }
  function click(event: MouseEvent) {
    if (!recording || actions.length >= 80 || !(event.target instanceof Element)) {
      return
    }
    const element = event.target.closest('button,a,[role="button"]')
    const label = element?.getAttribute('aria-label') || ''
    let kind: string | undefined
    if (label.startsWith('收起章节') || label.startsWith('展开章节')) {
      kind = 'chapter'
    } else if (label === '原位编辑公式') {
      kind = 'formula'
    } else if (label === '收起代码' || label === '展开全部代码') {
      kind = 'code-fold'
    } else if (element?.getAttribute('aria-haspopup') === 'menu') {
      kind = 'menu'
    } else if (element?.closest('.blog-toc-scroll')) {
      kind = 'toc'
    }
    if (kind) {
      actions.push({ time: Math.round(performance.now() - started), kind })
    }
  }
  const supported =
    typeof PerformanceObserver === 'undefined' ? [] : PerformanceObserver.supportedEntryTypes
  const entryType = ['long-animation-frame', 'longtask'].find((type) => supported.includes(type))
  const observer = !entryType
    ? undefined
    : new PerformanceObserver((list) => {
        if (recording) {
          for (const entry of list.getEntries()) {
            if (frames.length < 40) {
              const frame = entry.toJSON()
              frames.push({
                time: Math.round(frame.startTime - started),
                duration: frame.duration,
                blocking: frame.blockingDuration,
                scripts: frame.scripts
                  ?.slice(0, 8)
                  .map(
                    (script: {
                      duration: number
                      forcedStyleAndLayoutDuration: number
                      invoker: string
                      sourceURL?: string
                      sourceFunctionName?: string
                    }) => ({
                      duration: script.duration,
                      layout: script.forcedStyleAndLayoutDuration,
                      invoker: script.invoker,
                      source: script.sourceURL?.split('?')[0]?.replace(location.origin, ''),
                      name: script.sourceFunctionName,
                    }),
                  ),
              })
            }
          }
        }
      })
  if (entryType) {
    observer?.observe({ type: entryType, buffered: false })
  }
  const json = editor.getJSON
  const markdown = editor.getMarkdown
  const text = editor.getText
  const jsonProbe: Editor['getJSON'] = function (this: Editor, ...args) {
    const time = performance.now()
    try {
      return json.apply(this, args)
    } finally {
      if (recording) {
        exports.json++
        exports.milliseconds += performance.now() - time
      }
    }
  }
  const markdownProbe: Editor['getMarkdown'] = function (this: Editor, ...args) {
    const time = performance.now()
    try {
      return markdown.apply(this, args)
    } finally {
      if (recording) {
        exports.markdown++
        exports.milliseconds += performance.now() - time
      }
    }
  }
  const textProbe: Editor['getText'] = function (this: Editor, ...args) {
    const time = performance.now()
    try {
      return text.apply(this, args)
    } finally {
      if (recording) {
        exports.text++
        exports.milliseconds += performance.now() - time
      }
    }
  }
  editor.getJSON = jsonProbe
  editor.getMarkdown = markdownProbe
  editor.getText = textProbe
  // beforeTransaction 在 applyTransaction 之后触发，单靠它会漏掉校验及插件状态计算。
  // 仅采样期间包裹公开的 view 回调，停止后恢复原回调，不改变事务本身或执行顺序。
  const dispatch = view.props.dispatchTransaction
  const dispatchProbe = function (this: EditorView, transaction: Transaction) {
    const current = active
    if (current) {
      current.dispatchStart = performance.now()
    }
    try {
      dispatch!.call(this, transaction)
    } finally {
      if (current) {
        current.dispatchStart = undefined
      }
    }
  }
  if (dispatch) {
    view.setProps({ dispatchTransaction: dispatchProbe })
  }
  document.addEventListener('keydown', key, true)
  document.addEventListener('beforeinput', beforeInput, true)
  document.addEventListener('input', input, true)
  document.addEventListener('click', click, true)
  pane?.addEventListener('scroll', scroll, { passive: true })
  editor.on('beforeTransaction', beforeTransaction)
  editor.on('transaction', transaction)
  let result: string | undefined
  return {
    stop() {
      if (result !== undefined) {
        return result
      }
      recording = false
      observer?.disconnect()
      if (!editor.isDestroyed && view.props.dispatchTransaction === dispatchProbe) {
        view.setProps({ dispatchTransaction: dispatch })
      }
      document.removeEventListener('keydown', key, true)
      document.removeEventListener('beforeinput', beforeInput, true)
      document.removeEventListener('input', input, true)
      document.removeEventListener('click', click, true)
      pane?.removeEventListener('scroll', scroll)
      editor.off('beforeTransaction', beforeTransaction)
      editor.off('transaction', transaction)
      if (editor.getJSON === jsonProbe) {
        editor.getJSON = json
      }
      if (editor.getMarkdown === markdownProbe) {
        editor.getMarkdown = markdown
      }
      if (editor.getText === textProbe) {
        editor.getText = text
      }
      result = JSON.stringify({
        elapsed: Math.round(performance.now() - started),
        visibility: document.visibilityState,
        dom: {
          elements: root.querySelectorAll('*').length,
          paragraphs: root.querySelectorAll('p').length,
          math: root.querySelectorAll('.katex').length,
        },
        exports,
        samples,
        actions,
        frames,
      })
      return result
    },
  }
}
