<script setup lang="ts">
import type { DocumentResource } from '../../api/blogs'
const markdown = ref(
  '## 先从阅读开始，随时落笔\n\n好的编辑体验，不该让你在「正在写作」和「正在阅读」之间反复切换。**这里就是文章本身**：点击任意段落即可输入，选中文字即可设置格式，不需要先打开另一个编辑面板。\n\n这是一份独立的交互原型。你可以随意修改标题、正文和表格；所有修改只留在当前页面，刷新后恢复，不会影响现有博客。\n\n### 让内容保持清晰\n\n- 左侧目录跟随文章结构，点击标题即可定位光标。\n- 正文沿用访客阅读页的字体、行距和宽度。\n- 选中文字显示格式工具栏；空行输入 / 插入新的内容块。\n\n> 写作时只需要关心内容。保存、发布和文章设置应当安静地待在它们该在的位置。\n\n## 从 Markdown 到一篇文章\n\n导入只负责把文档带到这里，编辑器负责让修改自然发生。Markdown 始终是唯一的正文格式，阅读与编辑共享同一种视觉语言。\n\n| 内容 | 阅读效果 | 编辑方式 |\n| --- | --- | --- |\n| 段落与标题 | 清晰的层次与留白 | 直接点击输入 |\n| 表格与列表 | 沿用正文排版 | 在原位修改 |\n| 代码与图表 | 正式阅读器渲染 | 点击块上的编辑入口 |\n\n### 一段真实的代码\n\n代码使用正式阅读组件展示，保留语言、文件名、主题和复制操作。修改后回到相同的位置继续阅读。\n\n~~~typescript [article.ts]\ninterface Article {\n  title: string\n  markdown: string\n}\n\nfunction publishArticle(article: Article) {\n  return { ...article, published: true }\n}\n~~~\n\n## 图表也在文章里\n\n图表保持可拖动、缩放与全屏查看。需要修改时，在当前位置打开图表源码，而不是离开文章跳转到另一套页面。\n\n~~~mermaid\nflowchart LR\n  A[导入 Markdown] --> B[直接编辑正文]\n  B --> C[保存草稿]\n  C --> D[确认后发布]\n~~~\n\n## 收尾与下一步\n\n- [x] 阅读页一样的正文布局\n- [x] 点击正文直接输入\n- [x] 选区格式操作与斜杠菜单\n- [ ] 由你确认交互方向后，再接入真实保存流程\n\n你可以从这段文字开始，试着改写一个句子。\n\n## 公式与稳定图片\n\n行内公式 $x^2 + y^2 = z^2$，点击公式即可原位修改。\n\n$$\n\\frac{a+b}{2}\n$$\n\n![Vue 示例图片](resource://showcase-image)\n\n图片前后的文字可以连续选中、剪切并撤销，图片保持稳定资源引用。\n',
)
const metadata = ref({
  title: '文档导入全链路：从 Markdown 到可阅读的博客',
  slug: 'editor-showcase',
  summary: '让阅读与写作发生在同一个地方。',
  allowDownload: true,
  remoteRightsConfirmed: false,
})
const resources = ref<DocumentResource[]>([
  {
    key: 'showcase-image',
    type: 'IMAGE',
    status: 'READY',
    assetId: 42,
    resourceUrl: '/tech/vue.svg',
    expiresAt: null,
    originalUri: null,
  },
])
const backup = useImmersiveLocalBackup(markdown, metadata)

const fixtureLoading = ref(false)
const fixtureReady = ref(true)
const fixtureInfo = ref('')
const fixtureIssue = ref('')
async function loadFixture(restoreOnly = false) {
  fixtureLoading.value = true
  fixtureIssue.value = ''
  try {
    const data = await $fetch<{
      markdown: string
      name: string
      bytes: number
      resources: DocumentResource[]
    }>('/__poc/editor-fixture')
    resources.value = data.resources
    fixtureInfo.value =
      data.name +
      ' · ' +
      data.bytes.toLocaleString() +
      ' 字节 · ' +
      data.resources.length +
      ' 张配图 · 本机隔离草稿'
    if (!restoreOnly) {
      metadata.value = {
        ...metadata.value,
        title: data.name.replace(/\.md$/i, ''),
        slug: 'editor-showcase-local-fixture',
        summary: '真实学习笔记压力测试；未上传服务器。',
      }
      markdown.value = data.markdown
    }
  } catch (cause) {
    fixtureIssue.value = cause instanceof Error ? cause.message : '本地测试副本无法读取'
  } finally {
    fixtureLoading.value = false
    fixtureReady.value = true
  }
}
watch(
  backup.ready,
  (ready) => {
    if (ready && metadata.value.slug === 'editor-showcase-local-fixture') {
      fixtureReady.value = false
      void loadFixture(true)
    }
  },
  { flush: 'sync' },
)
const probeRoot = useTemplateRef<HTMLElement>('probeRoot')
let longTaskObserver: PerformanceObserver | undefined
onMounted(() => {
  if (
    typeof PerformanceObserver !== 'undefined' &&
    PerformanceObserver.supportedEntryTypes.includes('longtask')
  ) {
    let count = 0,
      longest = 0,
      total = 0
    longTaskObserver = new PerformanceObserver((list) => {
      for (const entry of list.getEntries()) {
        count++
        total += entry.duration
        longest = Math.max(longest, entry.duration)
      }
      if (probeRoot.value) {
        Object.assign(probeRoot.value.dataset, {
          longTasks: String(count),
          longestTask: longest.toFixed(1),
          longTaskTotal: total.toFixed(1),
        })
      }
    })
    longTaskObserver.observe({ type: 'longtask' })
  }
})
onBeforeUnmount(() => longTaskObserver?.disconnect())
</script>
<template>
  <div ref="probeRoot" class="flex min-h-0 flex-1 flex-col overflow-hidden" data-showcase-pressure>
    <div class="site-container flex flex-wrap items-center gap-3 py-2 text-xs text-muted">
      <UButton
        size="xs"
        variant="soft"
        icon="i-lucide-folder-open"
        :loading="fixtureLoading"
        @click="loadFixture(false)"
        >加载本地真实笔记</UButton
      >
      <span>{{ fixtureInfo || '仅开发环境；读取已准备的本地副本，不上传服务器' }}</span>
      <span v-if="fixtureIssue" role="alert">{{ fixtureIssue }}</span>
    </div>
    <EditorImmersiveWorkbench
      v-if="backup.ready.value && fixtureReady"
      v-model="markdown"
      v-model:metadata="metadata"
      :resources="resources"
      :backup-status="backup.status.value"
      :persist-local="backup.flush"
    />
  </div>
</template>
