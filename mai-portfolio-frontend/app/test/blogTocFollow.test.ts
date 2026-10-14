import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import { transpileModule } from 'typescript'
import { describe, expect, it, vi } from 'vitest'

// Execute the actual component script; no duplicate scroll implementation in this fixture.
const source = readFileSync(new URL('../components/blog/BlogTocTree.vue', import.meta.url), 'utf8').split('<script setup lang="ts">')[1]!.split('</script>')[0]!
const script = transpileModule(source, {}).outputText
function directory(heights = Array(18).fill(20) as number[], height = 120) {
  const props = { selected: '0' }, emit = vi.fn()
  let queued: (() => void) | undefined, changed = () => {}, mounted = () => {}, resized = () => {}
  const list = {
    clientHeight: height, clientTop: 0, scrollTop: 0, scrollHeight: heights.reduce((sum, value) => sum + value, 0),
    getBoundingClientRect: () => ({ top: 10 }), querySelector: () => items[Number(props.selected)],
    contains: (item: unknown) => items.includes(item as typeof items[number]),
  }
  const items = heights.map((value, index) => ({
    getBoundingClientRect: () => {
      const top = 10 + heights.slice(0, index).reduce((sum, h) => sum + h, 0) - list.scrollTop
      return { top, bottom: top + value, height: value }
    },
    compareDocumentPosition: (other: unknown) => items.indexOf(other as typeof items[number]) > index ? 4 : 2,
  }))
  const api = runInNewContext(script + '\n;({ navigate })', {
    exports: {}, defineProps: () => props, defineEmits: () => emit, useTemplateRef: () => ({ value: list }), Node: { DOCUMENT_POSITION_FOLLOWING: 4 },
    watch: (_: unknown, callback: () => void) => { changed = callback },
    onMounted: (callback: () => void) => { mounted = callback }, onBeforeUnmount: () => {},
    requestAnimationFrame: (callback: () => void) => { queued = callback; return 1 }, cancelAnimationFrame: () => {},
    ResizeObserver: class { constructor(callback: () => void) { resized = callback } observe() {} },
  }) as { navigate: (id: string) => void }
  function flush() { const callback = queued; queued = undefined; callback?.() }
  function select(index: number) { props.selected = String(index); changed() }
  mounted(); flush()
  return { list, items, emit, flush, select, api, resize: () => { resized(); flush() }, follow: (index: number) => { select(index); flush() } }
}

describe('bidirectional TOC scroll follow', () => {
  it('stays still at the bottom, then moves the next downward heading to the top', () => {
    const toc = directory()
    for (let i = 1; i <= 5; i++) { toc.follow(i); expect(toc.list.scrollTop).toBe(0) }
    toc.follow(6); expect(toc.list.scrollTop).toBe(120)
    for (let i = 7; i <= 11; i++) { toc.follow(i); expect(toc.list.scrollTop).toBe(120) }
    toc.follow(12); expect(toc.list.scrollTop).toBe(240)
  })
  it('corrects only missing space upward and stays still on a visible reversal', () => {
    const toc = directory(); toc.follow(5); toc.follow(6)
    for (let i = 5; i >= 3; i--) { toc.follow(i); expect(toc.list.scrollTop).toBe(i * 20) }
    toc.follow(4); expect(toc.list.scrollTop).toBe(60)
  })
  it('uses measured multiline heights and tolerates fractional edges', () => {
    const toc = directory([30, 39.5, 50, 60, 30, 80, 40])
    toc.follow(2); expect(toc.list.scrollTop).toBe(0)
    toc.follow(3); expect(toc.list.scrollTop).toBe(119.5)
    toc.follow(2); expect(toc.list.scrollTop).toBe(69.5)
  })
  it('shows the final coalesced selection and clamps at the end, including resize', () => {
    const toc = directory(); toc.select(2); toc.select(5); toc.select(10); toc.flush()
    expect(toc.list.scrollTop).toBe(100)
    toc.follow(17); expect(toc.list.scrollTop).toBe(240)
    toc.resize(); expect(toc.list.scrollTop).toBe(240)
  })
  it('does not page after clicking or fight manual TOC scrolling', () => {
    const toc = directory(); toc.follow(5); toc.api.navigate('6'); toc.follow(6)
    expect(toc.emit).toHaveBeenCalledWith('navigate', '6'); expect(toc.list.scrollTop).toBe(20)
    toc.list.scrollTop = 80; toc.follow(7); expect(toc.list.scrollTop).toBe(80)
    toc.list.scrollTop = 0; toc.follow(8); expect(toc.list.scrollTop).toBe(60)
  })
  it('does not carry a click on the current heading into the next chapter', () => {
    const toc = directory(); toc.follow(5); toc.api.navigate('5'); toc.follow(6)
    expect(toc.list.scrollTop).toBe(120)
  })
  it('ignores a previous heading hidden by branch folding', () => {
    const toc = directory(); toc.follow(5)
    toc.items[5]!.getBoundingClientRect = () => ({ top: 0, bottom: 0, height: 0 })
    toc.follow(6); expect(toc.list.scrollTop).toBe(20)
  })
})
