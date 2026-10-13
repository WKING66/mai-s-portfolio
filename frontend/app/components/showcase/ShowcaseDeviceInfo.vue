<script setup lang="ts">
import { useClipboard } from '@vueuse/core'
const info = shallowRef<Record<string, unknown> | null>(null)
const { copy, copied } = useClipboard({ legacy: true })
let frame = 0
function read() { frame = 0; info.value = { viewport: { width: innerWidth, height: innerHeight }, visualViewport: { width: visualViewport?.width, height: visualViewport?.height, scale: visualViewport?.scale }, dpr: devicePixelRatio, orientation: innerWidth > innerHeight ? 'Landscape' : 'Portrait', touchPoints: navigator.maxTouchPoints, secureContext: isSecureContext, userAgent: navigator.userAgent, address: location.origin + location.pathname } }
function schedule() { if (!frame) frame = requestAnimationFrame(read) }
onMounted(() => { read(); window.addEventListener('resize', schedule, { passive: true }); window.visualViewport?.addEventListener('resize', schedule, { passive: true }) })
onBeforeUnmount(() => { cancelAnimationFrame(frame); window.removeEventListener('resize', schedule); window.visualViewport?.removeEventListener('resize', schedule) })
</script>
<template>
  <UCard>
    <template #header><div class="flex items-center justify-between gap-4"><h3 class="font-semibold">当前设备与浏览器</h3><UButton color="neutral" variant="outline" size="sm" :disabled="!info" @click="copy(JSON.stringify(info, null, 2))">{{ copied ? '已复制' : '复制信息' }}</UButton></div></template>
    <p class="mb-4 text-sm leading-7 text-muted">真机验收时可复制这份信息。设备模拟与真实硬件结果分开记录；虚拟键盘、安全区和浏览器工具栏需要在手机上实际检查。</p>
    <pre v-if="info" class="max-w-full whitespace-pre-wrap break-all font-mono text-xs leading-6">{{ JSON.stringify(info, null, 2) }}</pre><p v-else class="text-sm text-muted">正在读取设备信息…</p>
  </UCard>
</template>
