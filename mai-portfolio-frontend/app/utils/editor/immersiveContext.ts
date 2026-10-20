import type { InjectionKey, Ref } from 'vue'
import type { CodeTheme } from '../../constants/codeThemes'
/** 编辑会话内展示偏好，不进入 Markdown；节点重建也不重置用户已选主题。 */
export const IMMERSIVE_CODE_THEMES: InjectionKey<Map<string, CodeTheme>> =
  Symbol('immersive-code-themes')

/** 弹层/输入控件归 UI 处理，contentDOM 中的文本选择、复制和输入仍归 ProseMirror。 */
export function stopImmersiveControlEvent({ event }: { event: Event }): boolean {
  return event.target instanceof Element && Boolean(event.target.closest('[data-editor-controls]'))
}

export const IMMERSIVE_OVERLAY_HOST: InjectionKey<Ref<HTMLElement | null>> =
  Symbol('immersive-overlay-host')
