<script setup lang="ts">
import { ref, watch } from 'vue'
import type { PublicProject } from '../api/projects'
import { PROJECT_CARD_TILT_RANGE, PROJECT_LINK_LABELS, PROJECT_MESSAGES } from '../constants/projects'

const props = defineProps<{ project: PublicProject }>()
const imageFailed = ref(false)
// 换封面后重新加载；404/网络失败退回真实无图排版，不显示伪造项目截图。
watch(() => props.project.cover?.url, () => { imageFailed.value = false })
</script>

<template>
  <SurfaceCard class="project-card flex self-start flex-col" :max-tilt="PROJECT_CARD_TILT_RANGE" data-project-card>
    <div v-if="project.cover && !imageFailed" class="project-cover">
      <img :src="project.cover.url" :alt="project.cover.alt || project.title" loading="lazy" decoding="async"
        class="block size-full object-cover object-top" @error="imageFailed = true">
    </div>
    <div class="flex flex-1 flex-col p-5 sm:p-6">
      <p v-if="project.timeLabel" class="mb-3 font-mono text-xs text-muted">{{ project.timeLabel }}</p>
      <h3 class="mb-3 break-words text-2xl font-extrabold tracking-tight">{{ project.title }}</h3>
      <p class="mb-4 whitespace-pre-wrap break-words leading-7 text-muted">{{ project.summary }}</p>
      <p v-if="project.contribution" class="mb-3 whitespace-pre-wrap break-words text-sm leading-6">
        <span class="font-semibold text-accent">{{ PROJECT_MESSAGES.contribution }}：</span>{{ project.contribution }}
      </p>
      <p v-if="project.outcome" class="mb-4 whitespace-pre-wrap break-words text-sm leading-6 text-muted">{{ PROJECT_MESSAGES.outcome }}：{{ project.outcome }}</p>
      <ul class="mb-5 flex flex-wrap gap-2" aria-label="项目技术标签">
        <li v-for="tag in project.tags" :key="tag.id" class="project-tag rounded-full border border-line px-3 py-1.5 text-xs">{{ tag.name }}</li>
      </ul>
      <div v-if="project.links.length" class="mt-auto flex flex-wrap gap-2">
        <a v-for="link in project.links" :key="link.type + link.url" :href="link.url" target="_blank"
          rel="noopener noreferrer" class="project-entry inline-flex items-center gap-1.5 rounded-full border border-line px-3.5 py-2 text-sm font-semibold no-underline"
          :class="link.type === 'DEMO' ? 'project-live' : 'text-accent'">
          {{ link.label || PROJECT_LINK_LABELS[link.type] }}<span aria-hidden="true">↗</span>
        </a>
      </div>
      <span v-if="imageFailed" role="status" class="sr-only">{{ PROJECT_MESSAGES.coverBroken }}</span>
    </div>
  </SurfaceCard>
</template>

<style scoped>
.project-card { border-radius: 18px; min-height: 300px; }
.project-cover { aspect-ratio: 2.8; border-radius: 17px 17px 0 0; overflow: hidden; border-bottom: 1px solid var(--site-line); background: var(--site-surface); }
.project-tag { background: color-mix(in srgb, var(--site-text) 4%, var(--site-surface)); }
.project-entry { transition: background-color .2s ease, border-color .2s ease; }
.project-entry:hover { border-color: var(--site-accent); background: color-mix(in srgb, var(--site-accent) 10%, transparent); }
.project-live { color: #071018; border-color: var(--site-accent); background: var(--site-accent); }
.project-live:hover { background: color-mix(in srgb, var(--site-accent) 85%, white); }
@media (prefers-reduced-motion: reduce) { .project-entry { transition: none; } }
</style>
