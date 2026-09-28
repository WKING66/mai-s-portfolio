<script setup lang="ts">
import { computed } from 'vue'
import type { TechTagResponse } from '../api/profile'

const props = defineProps<{ items: TechTagResponse[] }>()

interface TechArtwork {
  src: string
  darkSrc?: string
  wide?: boolean
  tall?: boolean
}

// 只映射已核对来源的素材；没有可用官方图标的技术显示名称，不伪造图形。
const artwork: Record<string, TechArtwork> = {
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

const categories = [
  { title: '编程语言', groups: ['LANGUAGE'] },
  { title: '框架与工具', groups: ['FRAMEWORK'] },
  { title: '工程与部署', groups: ['TOOL', 'INFRA'] },
  { title: '数据与存储', groups: ['DATA'] },
]

const cards = computed(() => categories.map(category => ({
  ...category,
  items: props.items.filter(item => category.groups.includes(item.group)),
})))

function keyOf(slug: string): string {
  return slug.replace(/^tech-/, '')
}
</script>

<template>
  <section id="stack" class="mx-auto mt-7 max-w-[980px] scroll-mt-24" aria-labelledby="stack-title">
    <h2 id="stack-title" class="sr-only">技术栈</h2>
    <div class="grid gap-4 md:grid-cols-2">
      <SurfaceCard v-for="(card, index) in cards" :key="card.title" :max-tilt="15"
        class="tech-card min-w-0 overflow-hidden px-4 py-3" :class="{ 'reverse-track': index % 2 === 1 }">
        <h3 class="text-[.98rem] font-bold">{{ card.title }}</h3>
        <div class="logo-window mt-1.5 overflow-hidden" :aria-label="card.title">
          <div class="logo-track flex w-max" :class="{ moving: card.items.length > 2 }">
            <ul v-for="copy in (card.items.length > 2 ? 2 : 1)" :key="copy"
              class="logo-row flex w-max list-none gap-[14px] p-0" :aria-hidden="copy === 2 ? 'true' : undefined"
              :aria-label="copy === 1 ? '技术列表' : undefined">
              <li v-for="item in card.items" :key="item.slug"
                class="grid h-[65px] w-[100px] shrink-0 content-center justify-items-center gap-1 text-center text-[.72rem] font-semibold"
                :class="{ 'wide-item': artwork[keyOf(item.slug)]?.wide, 'tall-lockup': artwork[keyOf(item.slug)]?.tall }">
                <template v-if="artwork[keyOf(item.slug)]">
                  <span class="logo-frame flex h-9 w-full items-center justify-center" :class="{ 'wide-logo': artwork[keyOf(item.slug)]?.wide }">
                    <img class="tech-logo logo-light" :src="artwork[keyOf(item.slug)]?.src" alt="" loading="lazy">
                    <img v-if="artwork[keyOf(item.slug)]?.darkSrc" class="tech-logo logo-dark"
                      :src="artwork[keyOf(item.slug)]?.darkSrc" alt="" loading="lazy">
                  </span>
                  <span v-if="!artwork[keyOf(item.slug)]?.wide">{{ item.name }}</span>
                  <span v-else class="sr-only">{{ item.name }}</span>
                </template>
                <span v-else class="no-logo flex min-h-9 items-center text-sm font-bold">{{ item.name }}</span>
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
  height: 115px;
  background-color: var(--site-tech-surface);
}
.logo-window {
  margin-inline: -16px;
  mask-image: linear-gradient(90deg, transparent, #000 7%, #000 93%, transparent);
}
.logo-row { padding-right: 14px; }
.tech-logo {
  max-width: 38px;
  max-height: 34px;
  object-fit: contain;
  filter: drop-shadow(0 0 5px rgb(255 255 255 / 18%));
}
.wide-item { width: 140px; }
.wide-logo .tech-logo { max-width: 132px; }
.tall-lockup { width: 100px; }
.tall-lockup .tech-logo { max-width: 68px; max-height: 45px; }
.logo-dark { display: none; }
.moving { animation: logo-slide 15s linear infinite; }
.reverse-track .moving { animation-direction: reverse; }
.tech-card:hover .moving, .tech-card:focus-within .moving { animation-play-state: paused; }
@keyframes logo-slide { to { transform: translateX(-50%); } }
@media (prefers-reduced-motion: reduce) {
  .logo-window { overflow-x: auto; mask-image: none; }
  .moving { animation: none; }
  .logo-row[aria-hidden="true"] { display: none; }
}
</style>

<style>
/* 主题状态挂在 html 上，不能交给组件 scoped 选择器匹配。 */
:root[data-theme="dark"] .tech-card .logo-light:has(+ .logo-dark) { display: none; }
:root[data-theme="dark"] .tech-card .logo-dark { display: block; }
@media (prefers-color-scheme: dark) {
  :root:not([data-theme="light"]) .tech-card .logo-light:has(+ .logo-dark) { display: none; }
  :root:not([data-theme="light"]) .tech-card .logo-dark { display: block; }
}
</style>
