import {
  validateCanvasMarkdown,
  type CanvasValidator,
} from '../../app/utils/editor/markdown/validation'

/** 只替代线程运输；数据转换仍运行真实的共享校验器。真实 Worker 另做浏览器验证。 */
export function directCanvasValidator(): CanvasValidator {
  const validate = validateCanvasMarkdown
  return { check: async (input) => validate(input), dispose() {} }
}
