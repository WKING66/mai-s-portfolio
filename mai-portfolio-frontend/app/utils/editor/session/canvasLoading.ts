import type { InjectionKey, ShallowRef } from 'vue'

export interface CanvasLoadingProgress {
  phase: string
  loaded?: number
  total?: number
}

/** 外层持续持有加载视图；内部读取、懒加载及画布绘制只报告进度，null 表示可展示正文或错误。 */
export const CANVAS_LOADING: InjectionKey<ShallowRef<CanvasLoadingProgress | null>> =
  Symbol('canvas-loading')
