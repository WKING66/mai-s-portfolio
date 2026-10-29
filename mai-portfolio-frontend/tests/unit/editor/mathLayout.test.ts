import { afterEach, describe, expect, it, vi } from 'vitest'
import { createMathLayoutObserver } from '../../../app/utils/editor/math/mathLayout'

afterEach(() => vi.unstubAllGlobals())

function nativeObserver() {
  let deliver: ResizeObserverCallback
  const observe = vi.fn()
  const unobserve = vi.fn()
  const disconnect = vi.fn()
  const constructor = vi.fn(function (callback: ResizeObserverCallback) {
    deliver = callback
    return { observe, unobserve, disconnect }
  })
  vi.stubGlobal('ResizeObserver', constructor)
  return {
    constructor,
    observe,
    unobserve,
    disconnect,
    resize(target: HTMLElement, width: number, height: number) {
      const box = { inlineSize: width, blockSize: height }
      deliver(
        [
          {
            target,
            contentRect: {
              x: 0,
              y: 0,
              top: 0,
              left: 0,
              right: width,
              bottom: height,
              width,
              height,
              toJSON: () => ({ width, height }),
            },
            borderBoxSize: [box],
            contentBoxSize: [box],
            devicePixelContentBoxSize: [box],
          },
        ],
        { observe, unobserve, disconnect },
      )
    },
  }
}
function formula() {
  const style = {
    contentVisibility: '',
    containIntrinsicSize: '',
    removeProperty(name: string) {
      if (name === 'content-visibility') {
        this.contentVisibility = ''
      }
      if (name === 'contain-intrinsic-size') {
        this.containIntrinsicSize = ''
      }
      return ''
    },
  }
  return { style } as unknown as HTMLElement
}

describe('公式屏外布局生命周期', () => {
  it('整张画布共享观察器；有真实非零尺寸后才启用屏外跳过，折叠的零尺寸不覆盖占位', () => {
    const native = nativeObserver()
    const layout = createMathLayoutObserver()
    const first = formula(),
      second = formula()
    layout.observe(first)
    layout.observe(second)
    expect(native.constructor).toHaveBeenCalledTimes(1)
    expect(first.style.contentVisibility).toBe('')
    native.resize(first, 120.5, 30.25)
    expect(first.style.contentVisibility).toBe('auto')
    expect(first.style.containIntrinsicSize).toBe('auto 120.5px auto 30.25px')
    native.resize(first, 0, 0)
    expect(first.style.containIntrinsicSize).toBe('auto 120.5px auto 30.25px')
    expect(second.style.contentVisibility).toBe('')
    layout.dispose()
  })

  it('编辑或卸载解除观察后，排队的旧回调不能重新启用跳过；画布销毁释放全部节点', () => {
    const native = nativeObserver()
    const layout = createMathLayoutObserver()
    const first = formula(),
      second = formula()
    const release = layout.observe(first)
    layout.observe(second)
    native.resize(first, 80, 25)
    release()
    native.resize(first, 90, 40)
    expect(first.style.contentVisibility).toBe('')
    expect(first.style.containIntrinsicSize).toBe('')
    expect(native.unobserve).toHaveBeenCalledWith(first)
    native.resize(second, 160, 70)
    layout.dispose()
    native.resize(second, 200, 90)
    expect(second.style.contentVisibility).toBe('')
    expect(native.disconnect).toHaveBeenCalledOnce()
  })

  it('缺少原生观察器时保持原来的完整渲染', () => {
    vi.stubGlobal('ResizeObserver', undefined)
    const layout = createMathLayoutObserver()
    const target = formula()
    layout.observe(target)()
    expect(target.style.contentVisibility).toBe('')
    layout.dispose()
  })
})
