<script setup lang="ts">
import { useBlogApi, type Blog } from '../api/blogs'
import BlogRenderer from './blog/BlogRenderer.vue'
import { useAssetApi, type AssetUsage } from '../api/assets'
import { useAssetUrlRefresh } from '../composables/useAssetUrlRefresh'
import {
  BLOG_IMAGE_ACCEPT,
  BLOG_INPUT_LIMITS,
  DOCUMENT_RESOURCE_STATUS_LABELS,
  DOCUMENT_RESOURCE_REFERENCE_PREFIX,
} from '../constants/blogs'
import { ASSET_REFERENCE_PREFIX } from '../constants/assets'
import { ADMIN_PATHS } from '../constants/admin'
import { ApiRequestError } from '../api/request'
import { blogSlugIssue } from '../utils/blogSlug'
const props = defineProps<{ id: number | null }>()
const api = useBlogApi()
const assets = useAssetApi()
const feedback = useFeedback()
const snapshot = ref<Blog | null>(null)
// 输入是可丢弃的编辑缓冲，snapshot 是服务端已提交版本；预览和导出不能读未提交输入。
const form = reactive({
  slug: '',
  title: '',
  summary: '',
  content: '',
  allowDownload: true,
  remoteRightsConfirmed: false,
})
type EditorOperation =
  | 'save'
  | 'publish'
  | 'unpublish'
  | 'image'
  | 'attachment'
  | 'export'
// 操作互斥，但加载提示属于实际触发的按钮，不能让上传、发布或导出冒充保存。
const activeOperation = ref<EditorOperation | null>(null)
const pending = computed(() => activeOperation.value !== null)
const loading = ref(props.id !== null)
const includeResources = ref(false)
const saveIssue = ref('')
const slugLocked = computed(() => Boolean(snapshot.value?.publishedAt))
const slugIssue = computed(() => slugLocked.value ? '' : blogSlugIssue(form.slug))
const canPublish = computed(() => Boolean(form.slug.trim() && form.title.trim() && form.content.trim()) && !slugIssue.value)
const imageInput = ref<HTMLInputElement | null>(null)
const attachmentInput = ref<HTMLInputElement | null>(null)
const unresolvedResources = computed(
  () =>
    snapshot.value?.resources.filter(
      (resource) => resource.status !== 'READY',
    ) ?? [],
)
function editorFields(value: Blog) {
  return {
    slug: value.slug ?? '',
    title: value.title ?? '',
    summary: value.summary ?? '',
    content: value.content ?? '',
    allowDownload: value.allowDownload,
  }
}
const isDirty = computed(() =>
  snapshot.value
    ? Object.entries(editorFields(snapshot.value)).some(
        ([key, value]) => form[key as keyof typeof form] !== value,
      )
    : true,
)
function accept(value: Blog) {
  // 只在明确加载、保存或状态变更成功后接收完整快照；后台 URL 续签不调用这里。
  snapshot.value = value
  Object.assign(form, editorFields(value))
  saveIssue.value = ''
  if (value.previewIssue) feedback.info(value.previewIssue)
}
async function load() {
  if (props.id === null) return
  loading.value = true
  try {
    accept(await api.get(props.id))
  } catch (e) {
    feedback.error(e)
  } finally {
    loading.value = false
  }
}
onMounted(load)
useAssetUrlRefresh(
  () => snapshot.value,
  async () => {
    if (!snapshot.value || props.id === null || pending.value) return
    const current = await api.get(props.id)
    // 签名地址刷新只更新展示字段，不能覆盖正在编辑的正文和表单。
    if (current.version === snapshot.value.version) {
      Object.assign(snapshot.value, {
        resources: current.resources,
        previewIssue: current.previewIssue,
      })
      if (current.previewIssue) feedback.info(current.previewIssue)
    } else feedback.info('文章已在其他位置修改，请重新读取已保存版本后编辑。')
  },
)
async function operation(name: EditorOperation, action: () => Promise<void>) {
  if (pending.value) return
  activeOperation.value = name
  try {
    await action()
  } catch (e) {
    if (name === 'save') {
      saveIssue.value = e instanceof ApiRequestError && e.status === 409
        ? '保存冲突：文章标识可能已被使用，或草稿版本已变化。输入已保留，请更换标识重试；若仍失败，请重新读取最新版本。'
        : e instanceof Error ? e.message : '草稿保存失败，输入已保留，请重试。'
      feedback.error(saveIssue.value)
    } else feedback.error(e)
  } finally {
    activeOperation.value = null
  }
}
async function save() {
  if (slugIssue.value) { feedback.error(slugIssue.value); return }
  saveIssue.value = ''
  await operation('save', async () => {
    const value = await api.save(props.id, {
      ...form,
      ...(snapshot.value ? { version: snapshot.value.version } : {}),
    })
    accept(value)
    feedback.success('草稿已保存。')
    if (props.id === null)
      await navigateTo(`${ADMIN_PATHS.blogs}/${value.id}`, { replace: true })
  })
}
async function status(published: boolean) {
  // 状态接口返回完整快照；未保存时接收它会吞掉输入，因此发布、下架共用这个保护。
  if (!snapshot.value || isDirty.value || published && !canPublish.value) return
  await operation(published ? 'publish' : 'unpublish', async () => {
    accept(
      await api.changeStatus(
        snapshot.value!.id,
        snapshot.value!.version,
        published,
      ),
    )
    feedback.success(published ? '文章已发布。' : '文章已下架。')
  })
}
async function upload(event: Event, usage: AssetUsage) {
  const input = event.currentTarget as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  await operation(usage === 'IMAGE' ? 'image' : 'attachment', async () => {
    const asset = await assets.upload(file, usage)
    const name = file.name.replace(/[\[\]\r\n]/g, '')
    form.content +=
      '\n\n' +
      (usage === 'IMAGE' ? '!' : '') +
      '[' +
      name +
      '](' +
      ASSET_REFERENCE_PREFIX +
      asset.id +
      ')\n'
    feedback.success('资源已上传，保存草稿后完成绑定。')
  })
  input.value = ''
}
async function download() {
  // 导出的来源始终是已保存版本，不把尚未保存的输入冒充成导出结果。
  if (snapshot.value && !isDirty.value)
    await operation('export', () =>
      api.download(snapshot.value!.id, includeResources.value, true),
    )
}
</script>
<template>
  <div v-if="loading" aria-label="读取文章" class="grid gap-5">
    <USkeleton class="h-12" /><USkeleton class="h-96" />
  </div>
  <UCard v-else-if="id !== null && !snapshot">
    <p class="mb-4 text-sm text-muted">文章暂时无法读取，请重试。</p>
    <UButton color="neutral" variant="outline" @click="load">重新读取</UButton>
  </UCard>
  <div
    v-else
    class="admin-content-layout"
    :aria-busy="pending"
  >
    <div class="min-w-0 space-y-6">
      <UCard>
        <template #header><h2 class="font-semibold">文章内容</h2></template>
        <div class="grid gap-5">
          <UFormField label="标题">
            <UInput
              v-model="form.title"
              :maxlength="BLOG_INPUT_LIMITS.title"
              :disabled="pending"
              placeholder="给文章起个标题"
              class="w-full"
              size="lg"
            />
          </UFormField>
          <UFormField label="文章标识" hint="Slug" :error="slugIssue || undefined"
            :description="slugLocked ? '首次发布后公开地址固定，下架后也不能修改文章标识。' : '用于公开地址；可以留空保存草稿，首次发布前必须填写并保存。'">
            <UInput id="article-slug" v-model="form.slug" :disabled="pending || slugLocked"
              :maxlength="BLOG_INPUT_LIMITS.slug" placeholder="填写这篇文章的独立标识"
              class="w-full" size="lg" @update:model-value="saveIssue = ''" />
            <p v-if="!form.slug && !slugLocked" class="mt-2 text-sm text-muted">尚未设置文章标识，请在这里填写后保存草稿，再发布文章。</p>
            <p v-else-if="!slugIssue" class="mt-2 break-all text-xs leading-6 text-muted">公开地址（发布后可访问）：<code>/blog/{{ encodeURIComponent(form.slug) }}</code></p>
            <p class="mt-2 text-xs leading-6 text-muted">文章标识独立于标题，不会随标题变化；是否重名由服务端在保存时检查。</p>
          </UFormField>
          <UAlert v-if="saveIssue" role="alert" color="error" variant="soft" title="草稿未保存" :description="saveIssue" />
          <UFormField
            label="摘要"
            description="用于博客列表的文章简介，可留空。"
          >
            <UTextarea
              v-model="form.summary"
              :maxlength="BLOG_INPUT_LIMITS.summary"
              :disabled="pending"
              placeholder="这篇文章主要讲什么？"
              class="w-full"
            />
          </UFormField>
          <div
            class="flex flex-wrap items-center justify-between gap-3 border-t border-default pt-4"
          >
            <h3 class="text-sm font-medium">Markdown 正文</h3>
            <div class="flex flex-wrap gap-2">
              <input
                ref="imageInput"
                type="file"
                :accept="BLOG_IMAGE_ACCEPT"
                hidden
                :disabled="pending"
                aria-label="选择正文图片"
                @change="upload($event, 'IMAGE')"
              />
              <input
                ref="attachmentInput"
                type="file"
                hidden
                :disabled="pending"
                aria-label="选择正文附件"
                @change="upload($event, 'ATTACHMENT')"
              />
              <UButton
                color="neutral"
                variant="outline"
                size="sm"
                icon="i-lucide-image-plus"
                :loading="activeOperation === 'image'"
                :disabled="pending"
                @click="imageInput?.click()"
                >插入图片</UButton
              >
              <UButton
                color="neutral"
                variant="outline"
                size="sm"
                icon="i-lucide-paperclip"
                :loading="activeOperation === 'attachment'"
                :disabled="pending"
                @click="attachmentInput?.click()"
                >插入附件</UButton
              >
            </div>
          </div>
          <UFormField
            label="正文"
            class="min-w-0"
            description="图片和附件上传后会插入资源引用；保存草稿后完成绑定。"
          >
            <UTextarea
              v-model="form.content"
              :disabled="pending"
              :rows="18"
              placeholder="使用 Markdown 开始写作…"
              class="w-full"
              :ui="{ base: 'font-mono leading-7' }"
            />
          </UFormField>
          <UCheckbox
            v-model="form.remoteRightsConfirmed"
            :disabled="pending"
            label="允许保存正文中的网络图片"
            description="仅当你拥有使用权时勾选；已上传的本地资源不需要勾选。"
          />
        </div>
      </UCard>
      <UCard v-if="unresolvedResources.length">
        <template #header>
          <div class="flex items-center gap-3">
            <h2 class="font-semibold">待修复资源</h2>
            <UBadge color="warning" variant="subtle"
              >{{ unresolvedResources.length }} 个</UBadge
            >
          </div>
        </template>
        <p class="mb-4 text-sm leading-6 text-muted">
          上传缺失文件后，用新的资源引用替换正文中的原引用；也可以移除不需要的引用。
        </p>
        <ul class="space-y-4 text-sm">
          <li
            v-for="resource in unresolvedResources"
            :key="resource.key"
            class="rounded-lg bg-elevated p-3"
          >
            <p class="break-all font-medium">
              {{ resource.originalUri || '未识别的资源' }}
            </p>
            <p class="mt-1 text-muted">
              {{ DOCUMENT_RESOURCE_STATUS_LABELS[resource.status] }}
            </p>
            <code class="mt-2 block break-all text-xs"
              >{{ DOCUMENT_RESOURCE_REFERENCE_PREFIX }}{{ resource.key }}</code
            >
          </li>
        </ul>
      </UCard>
      <UCard v-if="snapshot">
        <UCollapsible>
          <UButton
            color="neutral"
            variant="ghost"
            icon="i-lucide-eye"
            class="w-full justify-start"
            >已保存版本预览</UButton
          >
          <template #content>
            <p v-if="isDirty" class="mt-4 text-xs text-muted">
              当前有未保存修改；这里展示的仍是已保存版本。
            </p>
            <Suspense><BlogRenderer :key="snapshot.documentVersionId" :cache-key="'manage:' + snapshot.documentVersionId" :markdown="snapshot.content" :resources="snapshot.resources" class="mt-5 min-w-0" /></Suspense>
          </template>
        </UCollapsible>
      </UCard>
    </div>
    <aside class="min-w-0 space-y-5" aria-label="文章设置与操作">
      <UCard>
        <template #header>
          <div class="flex items-center justify-between gap-3">
            <h2 class="font-semibold">文章设置</h2>
            <UBadge
              :color="snapshot?.status === 'PUBLISHED' ? 'success' : 'neutral'"
              variant="subtle"
              >{{
                snapshot?.status === 'PUBLISHED' ? '已发布' : '草稿'
              }}</UBadge
            >
          </div>
        </template>
        <div class="grid gap-5">
          <UCheckbox
            v-model="form.allowDownload"
            :disabled="pending"
            label="允许登录访客下载文章"
          />
          <div
            class="border-t border-default pt-4 text-xs leading-6 text-muted"
          >
            <p>
              {{ snapshot ? `已保存版本 ${snapshot.version}` : '尚未保存' }} ·
              {{ isDirty ? '有未保存修改' : '内容已保存' }}
            </p>
            <p>保存只更新草稿，发布后才对外展示。</p>
          </div>
        </div>
        <template #footer>
          <div class="grid gap-3">
            <UButton
              block
              icon="i-lucide-save"
              :loading="activeOperation === 'save'"
              :disabled="pending || Boolean(slugIssue)"
              @click="save"
              >保存草稿</UButton
            >
            <UButton
              v-if="snapshot"
              block
              color="neutral"
              variant="outline"
              icon="i-lucide-send"
              :loading="activeOperation === 'publish'"
              :disabled="pending || isDirty || !canPublish"
              @click="status(true)"
              >发布当前草稿</UButton
            >
            <p v-if="snapshot && isDirty" class="text-xs text-muted">
              先保存修改，再发布、下架或导出。
            </p>
            <p v-if="snapshot && !canPublish" class="text-xs leading-6 text-muted">发布前请补全文章标识、标题和正文，并保存草稿。</p>
            <div v-if="snapshot" class="flex flex-wrap gap-2">
              <UButton
                color="neutral"
                variant="ghost"
                size="sm"
                :disabled="pending"
                @click="load"
                >{{ isDirty ? '放弃修改并重载' : '刷新已保存版本' }}</UButton
              >
              <UButton
                v-if="snapshot.status === 'PUBLISHED'"
                color="neutral"
                variant="ghost"
                size="sm"
                :disabled="pending || isDirty"
                :loading="activeOperation === 'unpublish'"
                @click="status(false)"
                >下架文章</UButton
              >
              <UButton
                v-if="snapshot.status === 'PUBLISHED'"
                :to="'/blog/' + snapshot.slug"
                color="neutral"
                variant="ghost"
                size="sm"
                icon="i-lucide-external-link"
                >查看公开文章</UButton
              >
            </div>
          </div>
        </template>
      </UCard>
      <UCard v-if="snapshot">
        <template #header><h2 class="font-semibold">导出文章</h2></template>
        <div class="grid gap-4">
          <p class="text-xs leading-5 text-muted">
            仅导出正文为 .md，资源使用访问链接；携带图片或附件为 ZIP。
          </p>
          <UCheckbox
            v-if="snapshot.resources.length"
            v-model="includeResources"
            :disabled="pending"
            label="包含图片和附件（ZIP）"
          />
          <UButton
            block
            color="neutral"
            variant="outline"
            icon="i-lucide-download"
            :loading="activeOperation === 'export'"
            :disabled="pending || isDirty"
            @click="download"
            >导出已保存草稿</UButton
          >
        </div>
      </UCard>
      <UButton
        :to="ADMIN_PATHS.blogs"
        color="neutral"
        variant="link"
        icon="i-lucide-arrow-left"
        >返回文章列表</UButton
      >
    </aside>
  </div>
</template>
