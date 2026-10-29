import {
  createImmersiveValidation,
  type ImmersiveValidator,
} from '../../app/utils/editor/markdown/immersiveValidation'

/** 只替代线程运输；数据转换仍运行真实的共享校验器。真实 Worker 另做浏览器验证。 */
export function directImmersiveValidator(): ImmersiveValidator {
  const validate = createImmersiveValidation()
  return { check: async (input) => validate(input), dispose() {} }
}
