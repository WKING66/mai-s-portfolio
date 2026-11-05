<script setup lang="ts">
import { ref, watch } from 'vue'
import type { PublicProject } from '../../api/projects'
import { PROJECT_LINK_LABELS, PROJECT_MESSAGES } from '../../constants/projects'

const props = defineProps<{ project: PublicProject; motion?: boolean; compactOnMobile?: boolean }>()
const expanded = ref(false)
const detailsId = useId()
const hasImageFailed = ref(false)
// 换封面后重新加载；404/网络失败退回真实无图排版，不显示伪造项目截图。
watch(
  () => props.project.cover?.url,
  () => {
    hasImageFailed.value = false
  },
)
</script>

<template>
  <UiSurfaceCard
    :motion="motion"
    class="project-card flex self-start flex-col"
    :class="{ 'project-card-compact': compactOnMobile, 'is-expanded': expanded }"
    data-project-card
  >
    <div v-if="project.cover && !hasImageFailed" class="project-cover">
      <img
        :src="project.cover.url"
        :alt="project.cover.alt || project.title"
        loading="lazy"
        decoding="async"
        class="block size-full object-cover object-top"
        @error="hasImageFailed = true"
      />
    </div>
    <div class="project-content flex flex-1 flex-col p-5 sm:p-6">
      <div class="project-overview">
        <p v-if="project.timeLabel" class="mb-3 font-mono text-xs text-muted">
          {{ project.timeLabel }}
        </p>
        <h3 class="mb-3 break-words text-xl font-semibold tracking-tight">{{ project.title }}</h3>
        <p class="project-summary mb-4 whitespace-pre-wrap break-words leading-7 text-muted">
          {{ project.summary }}
        </p>
      </div>
      <ul
        v-if="compactOnMobile && project.tags.length"
        class="project-quick-tags"
        aria-label="项目技术概览"
      >
        <li v-for="tag in project.tags.slice(0, 2)" :key="tag.id" class="project-tag">
          {{ tag.name }}
        </li>
        <li
          v-if="project.tags.length > 2"
          class="project-tag"
          :aria-label="'另有 ' + (project.tags.length - 2) + ' 项技术，展开详情查看'"
        >
          +{{ project.tags.length - 2 }}
        </li>
      </ul>
      <button
        v-if="compactOnMobile"
        type="button"
        class="project-details-toggle"
        :aria-expanded="expanded"
        :aria-controls="detailsId"
        :aria-label="(expanded ? '收起项目详情：' : '展开项目详情：') + project.title"
        @click="expanded = !expanded"
      >
        {{ expanded ? '收起详情' : '项目详情' }}
        <UIcon :name="expanded ? 'i-lucide-minus' : 'i-lucide-plus'" class="project-toggle-icon" />
      </button>
      <div :id="detailsId" class="project-details">
        <div class="project-details-inner">
          <p
            v-if="project.contribution"
            class="mb-3 whitespace-pre-wrap break-words text-sm leading-6"
          >
            <span class="font-semibold text-accent">{{ PROJECT_MESSAGES.contribution }}：</span
            >{{ project.contribution }}
          </p>
          <p
            v-if="project.outcome"
            class="mb-4 whitespace-pre-wrap break-words text-sm leading-6 text-muted"
          >
            {{ PROJECT_MESSAGES.outcome }}：{{ project.outcome }}
          </p>
          <ul class="mb-5 flex flex-wrap gap-2" aria-label="项目技术标签">
            <li
              v-for="tag in project.tags"
              :key="tag.id"
              class="project-tag rounded-md border border-line px-3 py-1.5 text-xs"
            >
              {{ tag.name }}
            </li>
          </ul>
          <div v-if="project.links.length" class="mt-auto flex flex-wrap gap-2">
            <a
              v-for="link in project.links"
              :key="link.type + link.url"
              :href="link.url"
              target="_blank"
              rel="noopener noreferrer"
              class="project-entry inline-flex items-center gap-1.5 rounded-lg border border-line px-3.5 py-2 text-sm font-semibold no-underline"
              :class="link.type === 'DEMO' ? 'project-live' : 'text-accent'"
            >
              {{ link.label || PROJECT_LINK_LABELS[link.type] }}<span aria-hidden="true">↗</span>
            </a>
          </div>
        </div>
      </div>
      <span v-if="hasImageFailed" role="status" class="sr-only">{{
        PROJECT_MESSAGES.coverBroken
      }}</span>
    </div>
  </UiSurfaceCard>
</template>

<style scoped>
.project-card {
  border-radius: var(--site-radius);
  min-height: 300px;
}
.project-cover {
  aspect-ratio: 2.8;
  border-radius: var(--site-radius) var(--site-radius) 0 0;
  overflow: hidden;
  border-bottom: 1px solid var(--site-line);
  background: var(--site-surface);
}
.project-tag {
  background: color-mix(in srgb, var(--site-text) 4%, var(--site-surface));
}
.project-quick-tags,
.project-details-toggle {
  display: none;
}
.project-details-inner {
  display: flex;
  flex: 1;
  flex-direction: column;
}
.project-details {
  flex: 1;
  display: flex;
}
.project-entry {
  transition:
    background-color 0.2s ease,
    border-color 0.2s ease;
}
.project-entry:hover {
  border-color: var(--site-accent);
  background: color-mix(in srgb, var(--site-accent) 10%, transparent);
}
.project-live {
  color: var(--site-inverted);
  border-color: var(--site-accent);
  background: var(--site-accent);
}
.project-live:hover {
  background: var(--site-primary-hover);
}
@media (max-width: 700px) {
  .project-card-compact {
    position: relative;
    min-height: 0;
    border: 0;
    border-inline-start: 2px solid transparent;
    border-bottom: 1px solid var(--site-line);
    border-radius: 0;
    padding: 20px 12px;
    background: transparent;
    box-shadow: none;
    transition:
      border-color 180ms ease,
      background-color 180ms ease;
  }
  .project-card-compact:is(:focus-within, .is-expanded) {
    border-inline-start-color: var(--site-accent);
    background: color-mix(in srgb, var(--site-accent) 5%, var(--site-bg));
  }
  .project-card-compact .project-content {
    padding: 0;
    min-width: 0;
  }
  .project-card-compact:has(.project-cover) .project-overview {
    padding-inline-end: 102px;
    min-height: 86px;
  }
  .project-card-compact .project-cover {
    position: absolute;
    inset-block-start: 20px;
    inset-inline-end: 12px;
    width: 86px;
    height: 82px;
    aspect-ratio: auto;
    margin: 0;
    border: 1px solid var(--site-line);
    border-radius: 8px;
    overflow: hidden;
  }
  .project-card-compact .project-cover img {
    transition: transform 220ms ease;
  }
  .project-card-compact .project-overview > p:first-child:not(.project-summary) {
    margin-bottom: 6px;
    font-size: 10px;
  }
  .project-card-compact .project-overview h3 {
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
    margin-bottom: 8px;
    font-size: 17px;
    line-height: 1.5;
    transition: color 180ms ease;
  }
  .project-card-compact .project-summary {
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
    font-size: 13px;
    line-height: 1.75;
    margin-bottom: 0;
  }
  .project-card-compact .project-quick-tags {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 5px;
    margin-top: 12px;
  }
  .project-card-compact .project-quick-tags .project-tag {
    max-width: 100%;
    overflow-wrap: anywhere;
    font-size: 10px;
    line-height: 1.5;
    padding: 4px 7px;
    border: 1px solid var(--site-line);
    border-radius: 5px;
  }
  .project-card-compact .project-details-toggle {
    display: inline-flex;
    align-items: center;
    justify-content: space-between;
    align-self: flex-start;
    gap: 12px;
    min-height: 44px;
    margin-top: 4px;
    font-size: 12px;
    color: var(--site-accent);
    cursor: pointer;
    border-radius: 6px;
    transition:
      background-color 160ms ease,
      color 160ms ease;
  }
  .project-card-compact .project-details-toggle:focus-visible {
    outline: 2px solid var(--site-accent);
    outline-offset: 3px;
  }
  .project-card-compact .project-details-toggle:active {
    background: var(--site-bg-accented);
  }
  .project-card-compact .project-toggle-icon {
    transition: transform 180ms ease;
  }
  .project-card-compact.is-expanded .project-toggle-icon {
    transform: rotate(180deg);
  }
  .project-card-compact .project-details {
    display: grid;
    grid-template-rows: 0fr;
    opacity: 0;
    visibility: hidden;
    transition:
      grid-template-rows 220ms ease,
      opacity 180ms ease,
      visibility 0s;
  }
  .project-card-compact .project-details-inner {
    min-height: 0;
    overflow: hidden;
  }
  .project-card-compact.is-expanded .project-details {
    grid-template-rows: 1fr;
    opacity: 1;
    visibility: visible;
    transition-delay: 0s;
  }
  .project-card-compact.is-expanded .project-details-inner {
    border-top: 1px solid var(--site-line);
    padding-top: 12px;
  }
  /* 简介收起时只占两行；详情打开后恢复完整文字。 */
  .project-card-compact.is-expanded .project-summary,
  .project-card-compact.is-expanded .project-overview h3 {
    display: block;
    -webkit-line-clamp: unset;
  }
}
@media (max-width: 700px) and (hover: hover) and (pointer: fine) {
  .project-card-compact:hover {
    border-inline-start-color: var(--site-accent);
    background: color-mix(in srgb, var(--site-accent) 5%, var(--site-bg));
    transform: none;
  }
  .project-card-compact:hover .project-overview h3 {
    color: var(--site-accent);
  }
  .project-card-compact:hover .project-cover img {
    transform: scale(1.04);
  }
}
@media (prefers-reduced-motion: reduce) {
  .project-entry,
  .project-card-compact,
  .project-card-compact .project-cover img,
  .project-card-compact .project-overview h3,
  .project-card-compact .project-details-toggle,
  .project-card-compact .project-toggle-icon,
  .project-card-compact .project-details {
    transition: none;
  }
  .project-card-compact:hover .project-cover img {
    transform: none;
  }
}
</style>
