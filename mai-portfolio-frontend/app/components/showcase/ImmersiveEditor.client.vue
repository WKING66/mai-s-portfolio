<script setup lang="ts">
import type { DocumentResource } from '../../api/blogs'
import type { JSONContent } from '@tiptap/core'
import { createImmersiveValidator } from '../../utils/editor/immersiveValidationClient'
const initialContent = shallowRef<string | JSONContent>(
  '## 先从阅读开始，随时落笔\n\n好的编辑体验，不该让你在「正在写作」和「正在阅读」之间反复切换。**这里就是文章本身**：点击任意段落即可输入，选中文字即可设置格式，不需要先打开另一个编辑面板。\n\n这是一份独立的交互原型。你可以随意修改标题、正文和表格；所有修改只留在当前页面；模拟保存不会上传服务器，刷新会回到示例内容。\n\n### 让内容保持清晰\n\n- 左侧目录跟随文章结构，点击标题即可定位光标。\n- 正文沿用访客阅读页的字体、行距和宽度。\n- 选中文字显示格式工具栏；空行输入 / 插入新的内容块。\n\n> 写作时只需要关心内容。保存、发布和文章设置应当安静地待在它们该在的位置。\n\n## 从 Markdown 到一篇文章\n\n导入只负责把文档带到这里，编辑器负责让修改自然发生。Markdown 始终是唯一的正文格式，阅读与编辑共享同一种视觉语言。\n\n| 内容 | 阅读效果 | 编辑方式 |\n| --- | --- | --- |\n| 段落与标题 | 清晰的层次与留白 | 直接点击输入 |\n| 表格与列表 | 沿用正文排版 | 在原位修改 |\n| 代码与图表 | 正式阅读器渲染 | 点击块上的编辑入口 |\n\n### 一段真实的代码\n\n代码使用正式阅读组件展示，保留语言、文件名、主题和复制操作。修改后回到相同的位置继续阅读。\n\n~~~typescript [article.ts]\ninterface Article {\n  title: string\n  markdown: string\n}\n\nfunction publishArticle(article: Article) {\n  return { ...article, published: true }\n}\n~~~\n\n## 图表也在文章里\n\n图表保持可拖动、缩放与全屏查看。需要修改时，在当前位置打开图表源码，而不是离开文章跳转到另一套页面。\n\n~~~mermaid\nflowchart LR\n  A[导入 Markdown] --> B[直接编辑正文]\n  B --> C[保存草稿]\n  C --> D[确认后发布]\n~~~\n\n## 收尾与下一步\n\n- [x] 阅读页一样的正文布局\n- [x] 点击正文直接输入\n- [x] 选区格式操作与斜杠菜单\n- [ ] 由你确认交互方向后，再接入真实保存流程\n\n你可以从这段文字开始，试着改写一个句子。\n\n## 公式与稳定图片\n\n行内公式 $x^2 + y^2 = z^2$，点击公式即可原位修改。\n\n$$\n\\frac{a+b}{2}\n$$\n\n![Vue 示例图片](resource://showcase-image)\n\n图片前后的文字可以连续选中、剪切并撤销，图片保持稳定资源引用。\n',
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
const workbench = useTemplateRef('workbench')
const documentKey = ref(0)

const fixtureLoading = ref(false)
const fixtureReady = ref(true)
const fixtureInfo = ref('')
const fixtureIssue = ref('')
const importer = createImmersiveValidator()
onBeforeUnmount(() => importer.dispose())
async function loadFixture() {
  if (workbench.value && !(await workbench.value.requestLeave())) {
    return
  }
  fixtureLoading.value = true
  fixtureIssue.value = ''
  const previousWorkbench = workbench.value
  const previousRevision = previousWorkbench?.getRevision()
  try {
    const data = await $fetch<{
      markdown: string
      name: string
      bytes: number
      resources: DocumentResource[]
    }>('/__poc/editor-fixture')
    // 导入先完成结构检查；失败不卸载现有画布，也不覆盖当前正文。
    const result = await importer.check({
      kind: 'canvas-import',
      markdown: data.markdown,
      resources: data.resources,
    })
    if (result.kind !== 'canvas-import') {
      throw new Error('文档导入结果不匹配，当前画布未改变。')
    }
    const document = result.document
    if (previousWorkbench !== workbench.value) {
      return
    }
    if (
      previousWorkbench?.getRevision() !== previousRevision &&
      !(await previousWorkbench?.requestLeave())
    ) {
      return
    }
    resources.value = data.resources
    fixtureInfo.value =
      data.name +
      ' · ' +
      data.bytes.toLocaleString() +
      ' 字节 · ' +
      data.resources.length +
      ' 张配图 · 本机隔离草稿'
    metadata.value = {
      ...metadata.value,
      title: data.name.replace(/\.md$/i, ''),
      slug: 'editor-showcase-local-fixture',
      summary: '真实学习笔记压力测试；未上传服务器。',
    }
    initialContent.value = document
    documentKey.value++
    await nextTick()
  } catch (cause) {
    fixtureIssue.value = cause instanceof Error ? cause.message : '本地测试副本无法读取'
  } finally {
    fixtureLoading.value = false
    fixtureReady.value = true
  }
}
</script>
<template>
  <div class="flex min-h-0 flex-1 flex-col overflow-hidden">
    <div class="site-container flex flex-wrap items-center gap-3 py-2 text-xs text-muted">
      <UButton
        size="xs"
        variant="soft"
        icon="i-lucide-folder-open"
        :loading="fixtureLoading"
        @click="loadFixture"
        >加载本地真实笔记</UButton
      >
      <span>{{ fixtureInfo || '仅开发环境；读取已准备的本地副本，不上传服务器' }}</span>
      <span v-if="fixtureIssue" role="alert">{{ fixtureIssue }}</span>
    </div>
    <EditorImmersiveWorkbench
      v-if="fixtureReady"
      :key="documentKey"
      ref="workbench"
      v-model:metadata="metadata"
      :initial-content="initialContent"
      :resources="resources"
    />
  </div>
</template>
