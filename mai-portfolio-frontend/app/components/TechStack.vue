<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, useTemplateRef } from 'vue'
import type { TechTagResponse } from '../api/profile'

const props = defineProps<{ items: TechTagResponse[] }>()
const stackElement = useTemplateRef<HTMLElement>('stack')
const isPaused = ref(false)
const isDocumentVisible = ref(true)
const isInView = ref(true)
let visibilityObserver: IntersectionObserver | undefined
const isAnimationStopped = computed(
  () => isPaused.value || !isDocumentVisible.value || !isInView.value,
)
// 1. 页面进入后台或技术栈离开可视区时暂停动画，避免无效的持续绘制。
function syncVisibility() {
  isDocumentVisible.value = document.visibilityState === 'visible'
}
onMounted(() => {
  syncVisibility()
  document.addEventListener('visibilitychange', syncVisibility)
  visibilityObserver = new IntersectionObserver((entries) => {
    isInView.value = entries[0]?.isIntersecting ?? false
  })
  if (stackElement.value) {
    visibilityObserver.observe(stackElement.value)
  }
})
// 2. 卸载时释放可视观察器和文档监听，不跨路由保留动画状态更新。
onBeforeUnmount(() => {
  visibilityObserver?.disconnect()
  document.removeEventListener('visibilitychange', syncVisibility)
})

interface TechArtwork {
  src: string
  darkSrc?: string
  wide?: boolean
  tall?: boolean
}

// 只映射已核对来源的素材；没有可用官方图标的技术显示名称，不伪造图形。
const techArtworkBySlug: Record<string, TechArtwork> = {
  java: { src: '/tech/java-duke.png' },
  javascript: { src: '/tech/javascript.svg' },
  typescript: { src: '/tech/typescript.svg' },
  python: { src: '/tech/python.svg' },
  'spring-boot': { src: '/tech/spring-boot.svg' },
  vue: { src: '/tech/vue.svg' },
  vite: { src: '/tech/vite-mark.svg' },
  langchain: { src: '/tech/langchain.svg' },
  langgraph: { src: '/tech/langgraph-light.svg', darkSrc: '/tech/langgraph-dark.png', wide: true },
  'mybatis-plus': { src: '/tech/mybatis-plus.svg' },
  git: { src: '/tech/git.svg' },
  jenkins: { src: '/tech/jenkins.svg' },
  'github-ci-cd': { src: '/tech/github-actions.svg' },
  linux: { src: '/tech/linux.png' },
  docker: { src: '/tech/docker.svg' },
  nginx: { src: '/tech/nginx.png', wide: true },
  elasticsearch: { src: '/tech/elastic.svg' },
  redis: { src: '/tech/redis.png' },
  postgresql: { src: '/tech/postgresql.png' },
  mysql: { src: '/tech/mysql-user-provided.png', wide: true, tall: true },
  milvus: { src: '/tech/milvus.svg' },
}

const techCategoryDefinitions = [
  { title: '编程语言', groups: ['LANGUAGE'] },
  { title: '框架与工具', groups: ['FRAMEWORK'] },
  { title: '工程与部署', groups: ['TOOL', 'INFRA'] },
  { title: '数据与存储', groups: ['DATA'] },
]

// 3. 按后端技术分组映射展示卡片；素材缺失时保持文字可读。
const techCategories = computed(() =>
  techCategoryDefinitions.map((category) => ({
    ...category,
    items: props.items.filter((item) => category.groups.includes(item.group)),
  })),
)

function techArtworkKey(slug: string): string {
  return slug.replace(/^tech-/, '')
}
</script>

<template>
  <section
    id="stack"
    ref="stack"
    class="mt-8"
    :data-paused="isAnimationStopped"
    aria-labelledby="stack-title"
  >
    <h2 id="stack-title" class="sr-only">技术栈</h2>
    <div class="motion-control mb-2 flex justify-end">
      <UButton
        color="neutral"
        variant="ghost"
        size="xs"
        :icon="isPaused ? 'i-lucide-play' : 'i-lucide-pause'"
        :aria-label="isPaused ? '继续技术栈滚动' : '暂停技术栈滚动'"
        :aria-pressed="isPaused"
        @click="isPaused = !isPaused"
        >{{ isPaused ? '继续展示' : '暂停展示' }}</UButton
      >
    </div>
    <div class="grid gap-4 md:grid-cols-2">
      <SurfaceCard
        v-for="(card, index) in techCategories"
        :key="card.title"
        motion
        class="tech-card min-w-0 p-5"
        :class="{ 'reverse-track': index % 2 === 1 }"
      >
        <h3 class="text-[.98rem] font-bold">{{ card.title }}</h3>
        <div class="logo-window mt-3" :aria-label="card.title">
          <div class="logo-track flex w-max" :class="{ moving: card.items.length > 2 }">
            <ul
              v-for="copy in card.items.length > 2 ? 2 : 1"
              :key="copy"
              class="logo-row flex list-none gap-3 p-0"
              :aria-label="copy === 1 ? '技术列表' : undefined"
              :aria-hidden="copy === 2 ? 'true' : undefined"
            >
              <li
                v-for="item in card.items"
                :key="item.slug"
                class="grid h-[65px] w-[100px] shrink-0 content-center justify-items-center gap-1 text-center text-[.72rem] font-semibold"
                :class="{
                  'wide-item': techArtworkBySlug[techArtworkKey(item.slug)]?.wide,
                  'tall-lockup': techArtworkBySlug[techArtworkKey(item.slug)]?.tall,
                }"
              >
                <template v-if="techArtworkBySlug[techArtworkKey(item.slug)]">
                  <span
                    class="logo-frame flex h-9 w-full items-center justify-center"
                    :class="{ 'wide-logo': techArtworkBySlug[techArtworkKey(item.slug)]?.wide }"
                  >
                    <img
                      class="tech-logo logo-light"
                      :src="techArtworkBySlug[techArtworkKey(item.slug)]?.src"
                      alt=""
                      loading="lazy"
                    />
                    <img
                      v-if="techArtworkBySlug[techArtworkKey(item.slug)]?.darkSrc"
                      class="tech-logo logo-dark"
                      :src="techArtworkBySlug[techArtworkKey(item.slug)]?.darkSrc"
                      alt=""
                      loading="lazy"
                    />
                  </span>
                  <span v-if="!techArtworkBySlug[techArtworkKey(item.slug)]?.wide">{{
                    item.name
                  }}</span>
                  <span v-else class="sr-only">{{ item.name }}</span>
                </template>
                <span v-else class="no-logo flex min-h-9 items-center text-sm font-bold">{{
                  item.name
                }}</span>
              </li>
            </ul>
          </div>
        </div>
      </SurfaceCard>
    </div>
  </section>
</template>

<style scoped>
.tech-card {
  min-height: 115px;
}
.logo-window {
  container-type: inline-size;
  overflow: hidden;
}
.logo-row {
  width: max-content;
  min-width: 100cqw;
  padding-right: 0.75rem;
}
.moving .logo-row {
  justify-content: space-around;
}
.moving {
  animation: logo-slide 28s linear infinite;
}
.reverse-track .moving {
  animation-direction: reverse;
}
.tech-card:is(:hover, :focus-within) .moving,
[data-paused='true'] .moving {
  animation-play-state: paused;
}
@keyframes logo-slide {
  to {
    transform: translateX(-50%);
  }
}
@media (prefers-reduced-motion: reduce) {
  .motion-control,
  .logo-row[aria-hidden='true'] {
    display: none;
  }
  .logo-window {
    overflow: visible;
  }
  .logo-track {
    width: auto;
  }
  .moving {
    animation: none;
  }
  .logo-row {
    min-width: 0;
    width: auto;
    flex-wrap: wrap;
    padding-right: 0;
  }
}
.tech-logo {
  max-width: 38px;
  max-height: 34px;
  object-fit: contain;
}
.wide-item {
  width: 140px;
}
.wide-logo .tech-logo {
  max-width: 132px;
}
.tall-lockup {
  width: 100px;
}
.tall-lockup .tech-logo {
  max-width: 68px;
  max-height: 45px;
}
.logo-dark {
  display: none;
}
</style>

<style>
/* 主题状态挂在 html 上，不能交给组件 scoped 选择器匹配。 */
:root[data-theme='dark'] .tech-card .logo-light:has(+ .logo-dark) {
  display: none;
}
:root[data-theme='dark'] .tech-card .logo-dark {
  display: block;
}
@media (prefers-color-scheme: dark) {
  :root:not([data-theme='light']) .tech-card .logo-light:has(+ .logo-dark) {
    display: none;
  }
  :root:not([data-theme='light']) .tech-card .logo-dark {
    display: block;
  }
}
</style>
