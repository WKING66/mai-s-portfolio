<script setup lang="ts">
import type { PublicProject } from '../api/projects'
defineProps<{ project: PublicProject }>()
const linkLabels = { CODE: '代码仓库', DEMO: '在线演示', DOCUMENTATION: '项目文档', OTHER: '外部入口' }
</script>
<template>
  <SurfaceCard class="flex h-full min-h-[340px] flex-col p-7" data-project-card>
    <p v-if="project.timeLabel" class="mb-5 font-mono text-xs text-muted">{{ project.timeLabel }}</p>
    <h3 class="mb-4 break-words text-2xl font-semibold tracking-tight">{{ project.title }}</h3>
    <p class="mb-6 whitespace-pre-wrap break-words leading-7 text-muted">{{ project.summary }}</p>
    <p class="mb-4 whitespace-pre-wrap break-words leading-7"><span class="font-semibold text-accent">我的贡献：</span>{{ project.contribution }}</p>
    <p v-if="project.outcome" class="mb-4 whitespace-pre-wrap break-words leading-7 text-muted">成果：{{ project.outcome }}</p>
    <ul class="mb-6 flex flex-wrap gap-2" aria-label="项目技术标签">
      <li v-for="tag in project.tags" :key="tag.id" class="rounded-full border border-line px-3 py-1 text-xs">{{ tag.name }}</li>
    </ul>
    <div v-if="project.links.length" class="mt-auto flex flex-wrap gap-4 border-t border-line pt-5">
      <a v-for="link in project.links" :key="link.type + link.url" :href="link.url" target="_blank"
        rel="noopener noreferrer" class="font-semibold text-accent underline underline-offset-4">{{ link.label || linkLabels[link.type] }} ↗</a>
    </div>
  </SurfaceCard>
</template>
