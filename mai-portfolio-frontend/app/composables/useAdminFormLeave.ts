import { toValue, type MaybeRefOrGetter } from 'vue'
import { AUTH_ACCESS } from '../constants/auth'

/** 表单与弹窗共用离开保护；权限已撤销时不能用未保存提示阻止退出后台。 */
export function useAdminFormLeave(
  dirty: MaybeRefOrGetter<boolean>,
  pending: MaybeRefOrGetter<boolean>,
) {
  const auth = useAuthState()
  const leaveOpen = ref(false)
  let resolveLeave: ((leave: boolean) => void) | undefined
  function decideLeave(leave: boolean) {
    leaveOpen.value = false
    resolveLeave?.(leave)
    resolveLeave = undefined
  }
  function requestLeave(): Promise<boolean> {
    if (!auth.canAccess(AUTH_ACCESS.OWNER)) {
      return Promise.resolve(true)
    }
    if (toValue(pending)) {
      return Promise.resolve(false)
    }
    if (!toValue(dirty)) {
      return Promise.resolve(true)
    }
    if (resolveLeave) {
      return Promise.resolve(false)
    }
    leaveOpen.value = true
    return new Promise((resolve) => {
      resolveLeave = resolve
    })
  }
  function beforeUnload(event: BeforeUnloadEvent) {
    if (toValue(dirty) || toValue(pending)) {
      event.preventDefault()
      event.returnValue = ''
    }
  }
  onMounted(() => window.addEventListener('beforeunload', beforeUnload))
  onBeforeUnmount(() => {
    window.removeEventListener('beforeunload', beforeUnload)
    decideLeave(false)
  })
  onBeforeRouteLeave(requestLeave)
  onBeforeRouteUpdate(requestLeave)
  return { leaveOpen, requestLeave, decideLeave }
}
