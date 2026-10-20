import { ref, watch, onMounted, onBeforeUnmount, type Ref } from 'vue'
const BACKUP_KEY = 'mai-portfolio:editor-showcase:draft:v1'
/** Showcase 独立本机备份，不写服务器，也不与正式文章草稿共用存储键。 */
export function useImmersiveLocalBackup<T extends Record<string, unknown>>(
  markdown: Ref<string>,
  metadata: Ref<T>,
) {
  const ready = ref(false)
  const status = ref('正在读取本机草稿…')
  let timer: ReturnType<typeof setTimeout> | undefined
  let lastSaved = ''
  function flush() {
    clearTimeout(timer)
    if (!ready.value) {
      return false
    }
    try {
      const payload = JSON.stringify({
        version: 1,
        markdown: markdown.value,
        metadata: metadata.value,
      })
      if (payload !== lastSaved) {
        localStorage.setItem(BACKUP_KEY, payload)
        lastSaved = payload
      }
      status.value = '已自动备份到本机，未上传服务器'
      return true
    } catch {
      status.value = '本机备份不可用，当前输入仍保留在页面中'
      return false
    }
  }
  onMounted(() => {
    try {
      const saved = localStorage.getItem(BACKUP_KEY)
      if (saved) {
        const value = JSON.parse(saved)
        if (
          value.version !== 1 ||
          typeof value.markdown !== 'string' ||
          !value.metadata ||
          typeof value.metadata !== 'object'
        ) {
          throw new Error('Invalid backup')
        }
        markdown.value = value.markdown
        metadata.value = Object.fromEntries(
          Object.entries(metadata.value).map(([key, current]) => [
            key,
            typeof value.metadata[key] === typeof current ? value.metadata[key] : current,
          ]),
        ) as T
        lastSaved = JSON.stringify({
          version: 1,
          markdown: markdown.value,
          metadata: metadata.value,
        })
        status.value = '已恢复本机草稿，未上传服务器'
      } else {
        status.value = '修改后自动备份到本机，未上传服务器'
      }
    } catch {
      status.value = '本机草稿读取失败，原备份未删除；当前示例可继续编辑'
    }
    ready.value = true
  })
  watch(
    [markdown, metadata],
    () => {
      if (ready.value) {
        clearTimeout(timer)
        timer = setTimeout(flush, 650)
      }
    },
    { deep: true },
  )
  onBeforeUnmount(() => {
    clearTimeout(timer)
  })
  return { ready, status, flush }
}
