<script setup lang="ts">
import type { ChatProgressPhase, TurnStatus } from '../../api/agentChat'
import type { DisplayPhase } from '../../composables/useSiteAssistant'

const props = defineProps<{
  livePhases?: DisplayPhase[]
  finishedPhases?: ChatProgressPhase[]
  status: TurnStatus
}>()

const labels: Record<DisplayPhase, string> = {
  queued: '等待开始',
  preparing: '正在准备回答',
  thinking: '正在思考',
  output: '正在输出回答',
}

const current = computed(() => props.livePhases?.at(-1))
const stages = computed(
  () => props.livePhases?.filter((phase) => phase !== 'output') ?? props.finishedPhases ?? [],
)
const expandedByDefault = computed(() => Boolean(current.value && current.value !== 'output'))
const summary = computed(() => {
  if (expandedByDefault.value && current.value) {
    return labels[current.value]
  }
  if (props.status === 'interrupted') {
    return '已停止生成 · 查看进度'
  }
  if (props.status === 'failed') {
    return '回答未完成 · 查看进度'
  }
  return '已完成准备 · 查看进度'
})
</script>

<template>
  <details v-if="stages.length" class="reply-progress" :open="expandedByDefault">
    <summary>
      <span
        class="reply-progress-indicator"
        :class="{ 'is-active': expandedByDefault }"
        aria-hidden="true"
      >
        <UIcon :name="expandedByDefault ? 'i-lucide-sparkles' : 'i-lucide-list-checks'" />
      </span>
      <span role="status">{{ summary }}</span>
      <UIcon class="reply-progress-chevron" name="i-lucide-chevron-right" aria-hidden="true" />
    </summary>
    <ol aria-label="回答阶段">
      <li
        v-for="phase in stages"
        :key="phase"
        :aria-current="phase === current ? 'step' : undefined"
      >
        {{ labels[phase] }}
      </li>
    </ol>
  </details>
  <p v-else-if="current === 'output'" class="reply-progress-output" role="status">
    {{ labels.output }}
  </p>
</template>

<style scoped>
.reply-progress,
.reply-progress-output {
  width: fit-content;
  max-width: 100%;
  margin: 0.65rem 0;
  color: var(--site-muted);
  font-size: 0.8125rem;
  line-height: 1.45;
}
.reply-progress summary {
  display: flex;
  width: fit-content;
  max-width: 100%;
  min-height: 2rem;
  align-items: center;
  gap: 0.45rem;
  padding: 0.125rem 0.375rem;
  border-radius: 0.375rem;
  list-style: none;
  cursor: pointer;
  transition:
    background-color 150ms ease,
    color 150ms ease;
}
.reply-progress summary::-webkit-details-marker {
  display: none;
}
.reply-progress summary:hover,
.reply-progress summary:focus-visible {
  color: var(--site-accent);
  background: var(--site-bg-elevated);
}
.reply-progress summary:focus-visible {
  outline: 2px solid var(--site-accent);
  outline-offset: 2px;
}
.reply-progress-indicator,
.reply-progress-chevron {
  display: inline-flex;
  flex: none;
}
.reply-progress-indicator.is-active {
  color: var(--site-accent);
}
.reply-progress-chevron {
  transition: transform 150ms ease;
}
.reply-progress[open] .reply-progress-chevron {
  transform: rotate(90deg);
}
.reply-progress ol {
  list-style: none;
  margin: 0.25rem 0 0.75rem 0.8rem;
  padding: 0;
}
.reply-progress li {
  position: relative;
  border-inline-start: 1px solid var(--site-line);
  padding: 0.25rem 0.875rem 0.375rem;
}
.reply-progress li::before {
  position: absolute;
  inset-inline-start: -0.24rem;
  inset-block-start: 0.66rem;
  width: 0.42rem;
  height: 0.42rem;
  border-radius: 50%;
  background: currentColor;
  content: '';
}
.reply-progress [aria-current='step'] {
  color: var(--site-accent);
  font-weight: 600;
}
@media (prefers-reduced-motion: reduce) {
  .reply-progress summary,
  .reply-progress-chevron {
    transition: none;
  }
}
@media (prefers-reduced-motion: no-preference) {
  .reply-progress[open] ol,
  .reply-progress[open] li:last-child {
    animation: progress-enter 180ms ease-out both;
  }
  .reply-progress-indicator.is-active {
    animation: progress-pulse 1.65s ease-in-out 2;
  }
}
@keyframes progress-enter {
  from {
    opacity: 0;
    transform: translateY(-3px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
@keyframes progress-pulse {
  50% {
    opacity: 0.48;
  }
}
</style>
