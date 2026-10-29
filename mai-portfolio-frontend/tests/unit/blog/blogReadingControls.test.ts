import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import { transpileModule } from 'typescript'
import { computed, ref, shallowRef } from 'vue'
import { describe, expect, it, vi } from 'vitest'

// 直接执行组件脚本，验证真实交互函数；不在测试中复制拖动和适配算法。
function componentScript(name: string) {
  const source = readFileSync(
    new URL('../../../app/components/blog/' + name + '.vue', import.meta.url),
    'utf8',
  )
  return transpileModule(source.split('<script setup lang="ts">')[1]!.split('</script>')[0]!, {})
    .outputText
}

function resizeHandle() {
  let mounted = () => {},
    disposed = () => {},
    resized = (_entries: unknown[]) => {}
  let queued: (() => void) | undefined
  const layout = {
    style: { setProperty: vi.fn(), removeProperty: vi.fn() },
    setAttribute: vi.fn(),
    removeAttribute: vi.fn(),
    getBoundingClientRect: () => ({ left: 100, width: 1200 }),
  }
  let captured = false
  const handle = {
    closest: () => layout,
    setPointerCapture: vi.fn(() => {
      captured = true
    }),
    releasePointerCapture: vi.fn(() => {
      captured = false
    }),
    hasPointerCapture: () => captured,
  }
  const disconnect = vi.fn()
  const api = runInNewContext(
    componentScript('BlogTocResizeHandle') + ';({start, move, finish, keyboard, percentage});',
    {
      ref,
      shallowRef,
      useTemplateRef: () => ({ value: handle }),
      onMounted: (callback: () => void) => {
        mounted = callback
      },
      onBeforeUnmount: (callback: () => void) => {
        disposed = callback
      },
      requestAnimationFrame: (callback: () => void) => {
        queued = callback
        return 1
      },
      cancelAnimationFrame: () => {
        queued = undefined
      },
      ResizeObserver: class {
        constructor(callback: typeof resized) {
          resized = callback
        }
        observe() {}
        disconnect = disconnect
      },
      document: { documentElement: {} },
      getComputedStyle: () => ({ fontSize: '16px' }),
    },
  )
  function measure(width: number) {
    resized([{ contentRect: { width } }])
  }
  function flush() {
    const callback = queued
    queued = undefined
    callback?.()
  }
  function key(key: string) {
    api.keyboard({ key, preventDefault() {}, stopPropagation() {} })
  }
  mounted()
  measure(1200)
  flush()
  return { api, layout, handle, measure, flush, key, disposed, disconnect }
}

describe('reading TOC width controls', () => {
  it('starts at 18rem, coalesces pointer updates and keeps the width within bounds', () => {
    const view = resizeHandle()
    expect(view.api.percentage.value).toBe(24)
    view.api.start({ button: 0, pointerId: 1, preventDefault() {} })
    view.api.move({ pointerId: 2, clientX: 700 })
    expect(view.api.percentage.value).toBe(24)
    view.api.move({ pointerId: 1, clientX: 550 })
    view.api.move({ pointerId: 1, clientX: 580 })
    view.flush()
    expect(view.layout.style.setProperty).toHaveBeenLastCalledWith('--blog-toc-width', '40%')
    view.api.move({ pointerId: 1, clientX: 2000 })
    view.flush()
    expect(view.api.percentage.value).toBe(50)
    view.api.finish()
    expect(view.handle.releasePointerCapture).toHaveBeenCalledWith(1)
    view.api.move({ pointerId: 1, clientX: 400 })
    expect(view.api.percentage.value).toBe(50)
  })
  it('supports keyboard adjustment/reset, container resize and teardown', () => {
    const view = resizeHandle()
    view.key('ArrowRight')
    expect(view.api.percentage.value).toBe(26)
    view.key('ArrowLeft')
    expect(view.api.percentage.value).toBe(24)
    view.key('End')
    expect(view.api.percentage.value).toBe(50)
    view.key('Home')
    expect(view.api.percentage.value).toBeCloseTo((256 / 1200) * 100)
    view.key('Enter')
    expect(view.api.percentage.value).toBe(24)
    view.key('End')
    view.measure(1000)
    expect(view.api.percentage.value).toBe(50)
    view.disposed()
    expect(view.disconnect).toHaveBeenCalledOnce()
    expect(view.layout.style.removeProperty).toHaveBeenCalledWith('--blog-toc-width')
  })
})

it.each([false, true])(
  'Mermaid reset restores the fitted camera and resize following (fullscreen=%s)',
  (fullscreen) => {
    let mounted = () => {},
      resized = (_entries: unknown[]) => {}
    const viewport = { observe() {} }
    const plane = { querySelector: () => ({ viewBox: { baseVal: { width: 1000, height: 600 } } }) }
    const api = runInNewContext(
      componentScript('BlogMermaidCanvas') + ';({fit, zoomBy, camera, isAutoFitEnabled});',
      {
        ref,
        shallowRef,
        computed,
        defineProps: () => ({ fullscreen }),
        withDefaults: (props: unknown) => props,
        defineEmits: () => vi.fn(),
        defineExpose() {},
        useTemplateRef: (name: string) => ({ value: name === 'plane' ? plane : viewport }),
        onMounted: (callback: () => void) => {
          mounted = callback
        },
        onBeforeUnmount() {},
        nextTick: () => Promise.resolve(),
        watch() {},
        ResizeObserver: class {
          constructor(callback: typeof resized) {
            resized = callback
          }
          observe() {}
        },
      },
    )
    mounted()
    resized([{ contentRect: { width: 800, height: 400 } }])
    const fitted = { ...api.camera.value }
    api.zoomBy(2)
    expect(api.isAutoFitEnabled.value).toBe(false)
    expect(api.camera.value.scale).toBeGreaterThan(fitted.scale)
    api.fit()
    expect(api.camera.value).toEqual(fitted)
    expect(api.isAutoFitEnabled.value).toBe(true)
    resized([{ contentRect: { width: 600, height: 300 } }])
    expect(api.camera.value.scale).toBeCloseTo(268 / 600)
  },
)
