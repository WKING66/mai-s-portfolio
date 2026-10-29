<script setup lang="ts">
import type { Editor } from '@tiptap/core'
import type { startEditorPerformance } from '../../utils/editor/diagnostics/editorPerformance'

const props = defineProps<{ editor: Editor }>()
const open = ref(false)
const recording = ref(false)
const starting = ref(false)
const result = ref('')
const status = ref('仅在本机采集，不记录正文或输入字符，也不会保存文章。')
let session: ReturnType<typeof startEditorPerformance> | undefined
let timeout: ReturnType<typeof setTimeout> | undefined
let generation = 0

function stop() {
  if (session) {
    result.value = session.stop()
    session = undefined
    status.value = '采集已停止，可复制诊断结果。'
  }
  recording.value = false
  clearTimeout(timeout)
}
async function start() {
  const current = props.editor
  const token = ++generation
  starting.value = true
  try {
    const { startEditorPerformance } = await import('../../utils/editor/diagnostics/editorPerformance')
    if (token !== generation || current.isDestroyed) {
      return
    }
    stop()
    session = startEditorPerformance(current)
    recording.value = true
    result.value = ''
    status.value = '正在采集，请正常输入、滚动、折叠；最多采集 60 秒。'
    open.value = false
    // 自动停止不能突然抢走正在输入的焦点；结果等用户主动打开查看。
    timeout = setTimeout(stop, 60000)
  } catch {
    status.value = '无法启动性能采集，请重试。'
  } finally {
    if (token === generation) {
      starting.value = false
    }
  }
}
async function copy() {
  stop()
  try {
    await navigator.clipboard.writeText(result.value)
    status.value = '诊断已复制，可粘贴反馈。'
  } catch {
    status.value = '复制失败，请手动复制下方诊断。'
  }
}
function reset() {
  generation++
  starting.value = false
  stop()
}
watch(() => props.editor, reset)
onBeforeUnmount(reset)
</script>

<template>
  <UPopover v-model:open="open" :modal="false" :content="{ align: 'end' }">
    <UButton
      icon="i-lucide-activity"
      :color="recording ? 'error' : 'neutral'"
      variant="ghost"
      :aria-label="recording ? '性能采集中，点击查看' : '编辑性能采集'"
      :title="recording ? '性能采集中，点击停止或查看' : '编辑性能采集'"
      @mousedown.prevent
    />
    <template #content>
      <section class="w-88 max-w-[calc(100vw-2rem)] space-y-3 p-4" aria-label="编辑性能采集">
        <div class="flex items-center justify-between gap-3">
          <h2 class="text-sm font-medium">编辑性能采集</h2>
          <UButton
            icon="i-lucide-x"
            color="neutral"
            variant="ghost"
            size="xs"
            aria-label="关闭性能面板"
            @click="open = false"
          />
        </div>
        <p class="text-xs text-muted" role="status">{{ status }}</p>
        <div class="flex flex-wrap gap-2">
          <UButton v-if="!recording" size="sm" :loading="starting" @click="start">开始采集</UButton>
          <UButton v-else size="sm" color="error" @click="copy">停止并复制诊断</UButton>
          <UButton
            v-if="result && !recording"
            size="sm"
            color="neutral"
            variant="outline"
            @click="copy"
            >复制诊断</UButton
          >
        </div>
        <UTextarea
          v-if="result"
          :model-value="result"
          :rows="5"
          readonly
          class="w-full font-mono text-xs"
          aria-label="编辑性能诊断"
        />
      </section>
    </template>
  </UPopover>
</template>
