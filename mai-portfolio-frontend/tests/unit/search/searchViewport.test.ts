import { describe, expect, it, vi } from 'vitest'
import { scrollTextRect } from '../../../app/utils/search/domSearch'

function fixture(panel?: { top: number; bottom: number; left: number; right: number }) {
  const view = { innerHeight: 900, matchMedia: () => ({ matches: false }), scrollBy: vi.fn() }
  const document = {
    defaultView: view,
    querySelector: (selector: string) => {
      if (selector === '.site-header') {
        return { getBoundingClientRect: () => ({ bottom: 80 }) }
      }
      return panel ? { getBoundingClientRect: () => panel } : null
    },
  }
  return { view, element: { ownerDocument: document } as unknown as HTMLElement }
}
const match = { top: 200, bottom: 220, height: 20, left: 40, right: 160 }

describe('search match positioning around the draggable panel', () => {
  it('moves a covered reading match below a full-width search panel', () => {
    const { view, element } = fixture({ top: 160, bottom: 350, left: 0, right: 390 })
    scrollTextRect(match, element)
    const position = match.top - view.scrollBy.mock.calls[0]![0].top
    expect(position).toBeGreaterThan(350)
    expect(position + match.height).toBeLessThan(900)
  })
  it('uses the free area above a panel dragged to the lower screen', () => {
    const { view, element } = fixture({ top: 600, bottom: 800, left: 0, right: 390 })
    scrollTextRect({ ...match, top: 700, bottom: 720 }, element)
    const position = 700 - view.scrollBy.mock.calls[0]![0].top
    expect(position).toBeGreaterThan(80)
    expect(position + match.height).toBeLessThan(600)
  })
  it('does not move a visible match outside the panel horizontal footprint', () => {
    const { view, element } = fixture({ top: 160, bottom: 350, left: 700, right: 1100 })
    scrollTextRect(match, element)
    expect(view.scrollBy).not.toHaveBeenCalled()
  })
  it('scrolls the editor pane instead of the outer page', () => {
    const { view, element } = fixture({ top: 160, bottom: 350, left: 0, right: 390 })
    const scrollBy = vi.fn()
    const pane = {
      getBoundingClientRect: () => ({ top: 120, bottom: 800 }),
      scrollBy,
    } as unknown as HTMLElement
    scrollTextRect(match, element, pane)
    expect(scrollBy).toHaveBeenCalledOnce()
    expect(match.top - scrollBy.mock.calls[0]![0].top).toBeGreaterThan(350)
    expect(view.scrollBy).not.toHaveBeenCalled()
  })
  it('preserves a match already visible in the unobscured area', () => {
    const { view, element } = fixture({ top: 160, bottom: 350, left: 0, right: 390 })
    scrollTextRect({ ...match, top: 550, bottom: 570 }, element)
    expect(view.scrollBy).not.toHaveBeenCalled()
  })
})
