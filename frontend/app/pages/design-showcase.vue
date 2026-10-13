<script setup lang="ts">
import type { FormError, FormSubmitEvent } from '@nuxt/ui'
import type { TocLink } from 'comark/plugins/toc'
import { SEO_NOINDEX } from '#shared/utils/seo'
import {
  SHOWCASE_PORTAL, SHOWCASE_SPACING, SHOWCASE_COLORS, SHOWCASE_PALETTES,
  SHOWCASE_NAVIGATION, SHOWCASE_THEME_UI, SHOWCASE_MARKDOWN,
  type ShowcaseTheme,
} from '../constants/designShowcase'
import '../assets/css/design-showcase.css'
import { BLOG_MATH_FIXTURE } from '../constants/blogMathFixture'
import { BLOG_MERMAID_FIXTURE } from '../constants/blogMermaidFixture'

definePageMeta({ designShowcase: true })
// 默认生产不可访问；测试预览的开启属于部署环境配置，不来自 URL、Cookie 或浏览器状态。
if (!import.meta.dev && useRuntimeConfig().public.designShowcaseEnvironment !== 'test') {
  throw createError({ statusCode: 404, statusMessage: 'Page not found' })
}
useSeoMeta({ title: 'Design Showcase · 阿霾', robots: SEO_NOINDEX, description: '青绿与冷灰的独立设计样板。' })
// 页面卸载即丢弃；不调用 useSiteTheme，不写站点 Cookie / localStorage / html class。
const theme = ref<ShowcaseTheme>('light')
const toc = ref<TocLink[]>([])
const scrollBody = { padding: import.meta.client && getComputedStyle(document.documentElement).scrollbarGutter.includes('stable') ? 0 : true, margin: 0 }
const toast = useToast()
const modalOpen = ref(false)
const selectedTab = ref('published')
const form = reactive({ title: '', category: '工程实践', summary: '' })
const submitted = ref('')
const validate = (state: typeof form): FormError[] => state.title.trim() ? [] : [{ name: 'title', message: '请填写样例标题。' }]
function notify() { toast.add({ title: '样例操作已完成', description: '这是一条可关闭的界面反馈。', icon: 'i-lucide-circle-check', color: 'success' }) }
function onSubmit(event: FormSubmitEvent<typeof form>) {
  submitted.value = event.data.title
  notify()
}
const tabs = [{ label: '已发布', value: 'published', icon: 'i-lucide-book-open' }, { label: '草稿', value: 'draft', icon: 'i-lucide-file-pen-line' }]
const dropdown = [
  { label: '查看阅读样板', icon: 'i-lucide-book-open', to: '#reading' },
  { label: '打开写作样板', icon: 'i-lucide-pen-line', to: '#writing' },
  { label: '显示反馈', icon: 'i-lucide-bell', onSelect: notify },
]
</script>

<template>
  <div class="design-showcase" :data-showcase-theme="theme">
    <UTheme :ui="SHOWCASE_THEME_UI" :props="{ select: { content: { bodyLock: false, position: 'popper' } } }">
      <UApp :portal="SHOWCASE_PORTAL" :scroll-body="scrollBody" :toaster="{ position: 'top-center', portal: SHOWCASE_PORTAL, ui: { viewport: 'top-22' } }">
        <a href="#showcase-main" class="showcase-skip rounded-lg bg-default px-4 py-3 text-primary">跳到主要内容</a>
        <header class="showcase-header sticky top-0 z-30">
          <div class="showcase-container flex min-h-18 flex-wrap items-center justify-between gap-3 py-3">
            <a href="#showcase-main" class="flex items-center gap-3 font-semibold tracking-tight" aria-label="阿霾设计样板首页">
              <span class="grid size-8 place-items-center rounded-lg bg-primary text-inverted font-mono">M</span>
              <span>阿霾<span class="ml-3 hidden font-normal text-muted sm:inline">Design / 01</span></span>
            </a>
            <UNavigationMenu :items="SHOWCASE_NAVIGATION" aria-label="样板页导航" class="hidden md:flex" />
            <div class="flex items-center gap-2">
              <UBadge color="neutral" variant="outline" class="hidden sm:inline-flex">视觉样板</UBadge>
              <UButton :icon="theme === 'light' ? 'i-lucide-moon' : 'i-lucide-sun'" color="neutral" variant="outline"
                :aria-label="theme === 'light' ? '预览深色主题' : '预览浅色主题'" @click="theme = theme === 'light' ? 'dark' : 'light'" />
              <UDropdownMenu :items="SHOWCASE_NAVIGATION" :modal="false" class="md:hidden">
                <UButton icon="i-lucide-menu" color="neutral" variant="ghost" aria-label="打开样板页导航" />
              </UDropdownMenu>
            </div>
          </div>
        </header>

        <main id="showcase-main" class="showcase-container" tabindex="-1">
          <section class="grid gap-10 py-16 sm:py-24 lg:grid-cols-[1.35fr_1fr] lg:items-center" aria-labelledby="hero-title">
            <div>
              <p class="showcase-kicker mb-5">THOUGHTFUL DESIGN / 清晰的表达</p>
              <h1 id="hero-title" class="showcase-title">让界面安静下来，<br><span class="text-primary">让内容被看见。</span></h1>
              <p class="mt-6 max-w-xl text-base leading-8 text-muted">青绿与冷灰，清晰的层次与恰当的留白。为技术写作和个人作品，建立一套简单、连贯的视觉语言。</p>
              <div class="mt-8 flex flex-wrap gap-3">
                <UButton to="#reading" size="lg" trailing-icon="i-lucide-arrow-down-right">开始阅读</UButton>
                <UButton to="#components" size="lg" color="neutral" variant="outline">探索组件</UButton>
              </div>
              <p class="mt-6 font-mono text-xs text-muted">NUXT UI · MARKDOWN · LIGHT & DARK</p>
            </div>
            <UCard class="min-w-0">
              <div class="mb-6 flex items-center justify-between"><UBadge variant="soft">Design notes</UBadge><span class="font-mono text-xs text-muted">01 / 03</span></div>
              <UIcon name="i-lucide-notebook-pen" class="mb-4 size-8 text-primary" />
              <h2 class="text-2xl leading-snug font-semibold">清晰，是一种设计选择。</h2>
              <p class="mt-4 leading-7 text-muted">从一篇文章、一张卡片、一个按钮开始，让所有细节共享相同的节奏。</p>
              <div class="mt-8 flex items-center gap-3 border-t border-default pt-5"><span class="size-2 rounded-full bg-success" /><span class="text-sm text-muted">少一点装饰，多一点专注。</span></div>
            </UCard>
          </section>

          <section id="tokens" class="showcase-section" aria-labelledby="tokens-title">
            <div class="mb-8 flex flex-wrap items-end justify-between gap-4"><div><p class="showcase-kicker mb-3">01 / FOUNDATION</p><h2 id="tokens-title" class="showcase-section-title">设计规范</h2></div><p class="max-w-lg text-sm leading-6 text-muted">稳定的色彩、文字和空间，是页面之间的共同语言。</p></div>
            <div class="grid gap-6 lg:grid-cols-2">
              <UCard v-for="(palette, name) in SHOWCASE_PALETTES" :key="name">
                <template #header><h3 class="font-semibold">{{ name === 'light' ? '浅色调色板' : '深色调色板' }}</h3></template>
                <div class="grid grid-cols-2 gap-4 sm:grid-cols-4"><div v-for="(color, token) in palette" :key="token"><div class="showcase-swatch" :style="{ backgroundColor: color }" /><p class="mt-3 text-xs capitalize">{{ token }}</p><p class="mt-1 font-mono text-xs text-muted">{{ color }}</p></div></div>
              </UCard>
              <UCard><template #header><h3 class="font-semibold">Primary / Neutral / Semantic</h3></template><div class="flex flex-wrap gap-3"><UBadge v-for="color in SHOWCASE_COLORS" :key="color" :color="color" variant="soft" size="lg">{{ color }}</UBadge></div><p class="mt-5 text-sm leading-6 text-muted">主色强调动作；中性色组织信息；语义色表达状态。</p></UCard>
              <UCard><template #header><h3 class="font-semibold">文字层次 · Typography</h3></template><p class="text-3xl font-semibold tracking-tight">清晰的标题 / Clear headings</p><p class="mt-3 text-lg leading-8">中文与 English 自然衔接。</p><p class="mt-3 text-sm leading-7 text-muted">辅助文字保持可读，视觉重量低于正文。</p><p class="mt-4 font-mono text-sm">const clarity = true // 等宽代码字体</p></UCard>
            </div>
            <UCard class="mt-6"><template #header><h3 class="font-semibold">空间与表面 · Spacing & Surface</h3></template><div class="flex flex-wrap items-end gap-6"><div v-for="space in SHOWCASE_SPACING" :key="space"><div class="rounded-sm bg-primary/25" :style="{ width: space + 'px', height: space + 'px' }" /><p class="mt-2 font-mono text-xs text-muted">{{ space }}px</p></div><div class="ml-auto space-y-2 text-sm text-muted"><p>控件 8px · 卡片 12px</p><p>轻边框 · 默认无阴影</p><p>正文 16px · 阅读 17px / 1.85</p></div></div></UCard>
            <div class="mt-6 grid gap-6 sm:grid-cols-2"><UCard><p class="text-sm font-medium">边框表面</p><p class="mt-2 text-sm text-muted">默认卡片，用留白与轻边框区分层次。</p></UCard><UCard :style="{ boxShadow: 'var(--showcase-shadow)' }"><p class="text-sm font-medium">轻阴影表面</p><p class="mt-2 text-sm text-muted">仅在需要强调层次时使用适度阴影。</p></UCard></div>
          </section>

          <section id="components" class="showcase-section" aria-labelledby="components-title">
            <p class="showcase-kicker mb-3">02 / INTERACTION</p><h2 id="components-title" class="showcase-section-title mb-8">熟悉的组件，一致的反馈</h2>
            <div class="grid items-start gap-6 lg:grid-cols-2">
              <div class="space-y-6">
                <UCard><template #header><h3 class="font-semibold">按钮与状态</h3></template><div class="flex flex-wrap gap-3"><UButton @click="notify">主要操作</UButton><UButton variant="soft" @click="notify">柔和强调</UButton><UButton variant="outline" @click="notify">描边按钮</UButton><UButton color="neutral" variant="ghost" @click="notify">轻量操作</UButton><UButton disabled>暂不可用</UButton><UButton loading>处理中</UButton></div><p class="mt-5 text-sm text-muted">悬停观察反馈，使用 Tab 查看焦点状态。</p><div class="mt-5 flex flex-wrap gap-3"><UButton color="neutral" variant="outline" icon="i-lucide-panel-top" @click="modalOpen = true">打开对话框</UButton><UDropdownMenu :items="dropdown" :modal="false"><UButton color="neutral" variant="outline" trailing-icon="i-lucide-chevron-down">更多操作</UButton></UDropdownMenu><UButton color="neutral" variant="outline" icon="i-lucide-bell" @click="notify">显示 Toast</UButton></div></UCard>
                <UCard><template #header><h3 class="font-semibold">状态、标签与加载</h3></template><div class="flex flex-wrap gap-2"><UBadge color="success" variant="subtle">已发布</UBadge><UBadge color="neutral" variant="outline">草稿</UBadge><UBadge color="warning" variant="soft">待完善</UBadge><UBadge color="error" variant="soft">读取失败</UBadge></div><div class="mt-6 flex items-start gap-4" aria-label="文章加载样板"><USkeleton class="size-12 shrink-0 rounded-lg" /><div class="w-full space-y-3"><USkeleton class="h-4 w-2/3" /><USkeleton class="h-3 w-full" /><USkeleton class="h-3 w-4/5" /></div></div></UCard>
              </div>
              <UCard><template #header><h3 class="font-semibold">管理表单</h3><p class="mt-1 text-sm text-muted">试试空标题提交，或填写内容查看成功反馈。</p></template><UForm :state="form" :validate="validate" class="space-y-5" @submit="onSubmit">
                <UFormField label="样例标题" name="title" required><UInput v-model="form.title" placeholder="给这篇笔记一个标题" class="w-full" /></UFormField>
                <UFormField label="文章分类" name="category"><USelect v-model="form.category" :items="['工程实践', '设计笔记', '阅读记录']" class="w-full" /></UFormField>
                <UFormField label="样例摘要" name="summary"><UTextarea v-model="form.summary" placeholder="用一两句话说明这篇文章…" :rows="3" class="w-full" /></UFormField>
                <UFormField label="只读示例"><UInput model-value="由页面样例提供" disabled class="w-full" /></UFormField>
                <div class="flex items-center gap-4"><UButton type="submit">提交样例</UButton><span v-if="submitted" role="status" class="text-sm text-success">已提交：{{ submitted }}</span></div>
              </UForm></UCard>
            </div>
            <div class="mt-10"><UTabs v-model="selectedTab" :items="tabs" :content="false" class="mb-6 max-w-sm" aria-label="文章卡片状态" /><div class="grid gap-6 md:grid-cols-3"><UCard v-for="(title, index) in ['从边界开始理解系统', '让复杂的信息更容易阅读', '保持简单，也保持完整']" :key="title"><div class="mb-5 flex items-center justify-between"><UBadge :color="selectedTab === 'published' ? 'primary' : 'neutral'" variant="soft">{{ selectedTab === 'published' ? '工程笔记' : '草稿样例' }}</UBadge><span class="font-mono text-xs text-muted">0{{ index + 1 }}</span></div><h3 class="text-xl leading-8 font-semibold">{{ title }}</h3><p class="mt-3 text-sm leading-7 text-muted">关于结构、表达与日常开发的思考。从具体的问题出发，记录可以复用的经验。</p><UButton to="#reading" color="neutral" variant="link" trailing-icon="i-lucide-arrow-up-right" class="mt-6 p-0">阅读文章</UButton></UCard></div></div>
          </section>

          <section id="reading" class="showcase-section" aria-labelledby="reading-title">
            <div class="mb-10"><p class="showcase-kicker mb-3">03 / READING</p><h2 id="reading-title" class="showcase-section-title">为长文章，留出呼吸的空间</h2></div>
            <div class="grid min-w-0 gap-12 lg:grid-cols-[minmax(0,1fr)_13rem]">
              <div class="min-w-0"><ShowcaseArticle @toc="toc = $event" /><details class="mt-8 rounded-xl border border-default p-5"><summary class="cursor-pointer text-sm font-medium">查看固定 Markdown 样例</summary><pre class="mt-4 overflow-x-auto font-mono text-sm leading-7"><code>{{ SHOWCASE_MARKDOWN }}</code></pre></details></div>
              <aside class="showcase-sidebar self-start lg:sticky lg:top-28"><BlogToc :items="toc" /><p class="mt-8 text-xs leading-6 text-muted">正文优先。目录、日期与说明保持适度的视觉重量。</p></aside>
            </div>
          </section>

          <section id="writing" class="showcase-section" aria-labelledby="writing-title">
            <div class="mb-8"><p class="showcase-kicker mb-3">04 / WRITING</p><h2 id="writing-title" class="showcase-section-title">从阅读，到表达</h2><p class="mt-3 text-sm leading-7 text-muted">体验真实的富文本编辑，在源码与视觉模式之间切换。</p></div>
            <UCard><ClientOnly><LazyShowcaseEditor /><template #fallback><div aria-label="编辑器正在加载" class="space-y-5"><USkeleton class="h-10 w-full" /><USkeleton class="h-80 w-full" /></div></template></ClientOnly></UCard>
          </section>
          <section id="math-reading" class="showcase-section" aria-label="数学公式阅读样板">
            <div class="mb-8"><p class="showcase-kicker mb-3">05 / MATH</p><h2 class="showcase-section-title">数学公式与安全降级</h2></div>
            <BlogRenderer :markdown="BLOG_MATH_FIXTURE" cache-key="showcase:math" class="mx-auto max-w-190" />
          </section>
          <section id="mermaid-reading" class="showcase-section" aria-label="Mermaid 阅读样板">
            <SectionHeading eyebrow="DIAGRAMS" title="Mermaid 图表" description="按需渲染，保留图表源码与安全错误回退。" />
            <BlogRenderer :markdown="BLOG_MERMAID_FIXTURE" cache-key="showcase:mermaid" class="mx-auto max-w-190" />
          </section>
          <section id="device-info" class="showcase-section" aria-label="真机验收信息"><ShowcaseDeviceInfo /></section>
        </main>

        <footer class="border-t border-default"><div class="showcase-container flex flex-wrap items-center justify-between gap-4 py-8 text-sm text-muted"><p>阿霾 · 设计样板</p><p class="font-mono text-xs">CLARITY / CONSISTENCY / CONTENT</p><a href="#showcase-main" class="text-primary">回到顶部 ↑</a></div></footer>
        <div id="design-showcase-portals" />
        <UModal v-model:open="modalOpen" title="一个清晰的决定" description="对话框与页面共享同一套局部主题。">
          <template #body><p class="leading-7 text-muted">确认按钮、关闭操作与键盘焦点交给 Nuxt UI 管理。按 Escape 关闭，或用 Tab 在控件之间移动。</p></template>
          <template #footer><div class="flex w-full justify-end gap-3"><UButton color="neutral" variant="outline" @click="modalOpen = false">取消</UButton><UButton @click="modalOpen = false; notify()">确认样例</UButton></div></template>
        </UModal>
      </UApp>
    </UTheme>
  </div>
</template>
