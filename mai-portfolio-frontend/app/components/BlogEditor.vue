<script setup lang="ts">
import { useBlogApi, type Blog, type DocumentResource } from '../api/blogs'
import { useAssetApi, type AssetUsage } from '../api/assets'
import { useAssetUrlRefresh } from '../composables/useAssetUrlRefresh'
import {
  BLOG_IMAGE_ACCEPT,
  BLOG_INPUT_LIMITS,
  DOCUMENT_RESOURCE_REFERENCE_PREFIX,
} from '../constants/blogs'
import { ASSET_REFERENCE_PREFIX } from '../constants/assets'
import { ADMIN_PATHS } from '../constants/admin'
import { ApiRequestError } from '../api/request'
import { blogSlugIssue } from '../utils/blogSlug'
const props = defineProps<{ id: number | null }>()
const blogApi = useBlogApi()
const assetApi = useAssetApi()
const feedback = useFeedback()
const savedBlogPost = ref<Blog | null>(null)
// editorForm 是编辑草稿，实时预览读取它；savedBlogPost 是已提交快照，仅用于版本操作与导出。
const editorForm = reactive({
  slug: '',
  title: '',
  summary: '',
  content: '',
  allowDownload: true,
  remoteRightsConfirmed: false,
})
type EditorOperation = 'save' | 'publish' | 'unpublish' | 'image' | 'attachment' | 'export'
// 操作互斥，但加载提示属于实际触发的按钮，不能让上传、发布或导出冒充保存。
const activeOperation = ref<EditorOperation | null>(null)
const isOperationPending = computed(() => activeOperation.value !== null)
const isLoading = ref(props.id !== null)
const includeResources = ref(false)
const saveErrorMessage = ref('')
const isMarkdownBlocked = ref(false)
const isSlugLocked = computed(() => Boolean(savedBlogPost.value?.publishedAt))
const slugIssue = computed(() => (isSlugLocked.value ? '' : blogSlugIssue(editorForm.slug)))
const canPublish = computed(
  () =>
    Boolean(editorForm.slug.trim() && editorForm.title.trim() && editorForm.content.trim()) &&
    !slugIssue.value,
)
// 上传后的投影仅用于本次草稿预览，正文仍只插入 asset://；保存后改用服务端绑定资源。
const uploadedResources = shallowRef<DocumentResource[]>([])
const previewResources = computed(() => [
  ...(savedBlogPost.value?.resources ?? []),
  ...uploadedResources.value,
])
const imageInput = ref<HTMLInputElement | null>(null)
const attachmentInput = ref<HTMLInputElement | null>(null)
const unresolvedResources = computed(
  () => savedBlogPost.value?.resources.filter((resource) => resource.status !== 'READY') ?? [],
)
function extractEditorFields(blogPost: Blog) {
  return {
    slug: blogPost.slug ?? '',
    title: blogPost.title ?? '',
    summary: blogPost.summary ?? '',
    content: blogPost.content ?? '',
    allowDownload: blogPost.allowDownload,
  }
}
const isDirty = computed(
  () =>
    isMarkdownBlocked.value ||
    (savedBlogPost.value
      ? Object.entries(extractEditorFields(savedBlogPost.value)).some(
          ([key, value]) => editorForm[key as keyof typeof editorForm] !== value,
        )
      : true),
)
// 1. 只有明确读取、保存或状态变更成功才接收服务端快照，失败不覆盖编辑缓冲。
function acceptSavedBlogPost(blogPost: Blog) {
  // 后台 URL 续签不会调用这里，不能把资源投影刷新误当作接收正文。
  savedBlogPost.value = blogPost
  uploadedResources.value = []
  Object.assign(editorForm, extractEditorFields(blogPost))
  saveErrorMessage.value = ''
  if (blogPost.previewIssue) {
    feedback.info(blogPost.previewIssue)
  }
}
async function loadBlogPost() {
  if (props.id === null) {
    return
  }
  isLoading.value = true
  try {
    acceptSavedBlogPost(await blogApi.get(props.id))
  } catch (cause) {
    feedback.error(cause)
  } finally {
    isLoading.value = false
  }
}
onMounted(loadBlogPost)
// 2. 地址续签仅更新同版本资源投影，不覆盖正文及未保存输入。
useAssetUrlRefresh(
  () => savedBlogPost.value,
  async () => {
    if (!savedBlogPost.value || props.id === null || isOperationPending.value) {
      return
    }
    const latestBlogPost = await blogApi.get(props.id)
    // 签名地址刷新只更新展示字段，不能覆盖正在编辑的正文和表单。
    if (latestBlogPost.version === savedBlogPost.value.version) {
      Object.assign(savedBlogPost.value, {
        resources: latestBlogPost.resources,
        previewIssue: latestBlogPost.previewIssue,
      })
      if (latestBlogPost.previewIssue) {
        feedback.info(latestBlogPost.previewIssue)
      }
    } else {
      feedback.info('文章已在其他位置修改，请重新读取已保存版本后编辑。')
    }
  },
)
// 3. 保存、发布、上传和导出共用互斥入口，错误处理后始终释放当前操作。
async function runEditorOperation(name: EditorOperation, action: () => Promise<void>) {
  if (isOperationPending.value) {
    return
  }
  activeOperation.value = name
  try {
    await action()
  } catch (cause) {
    if (name === 'save') {
      if (cause instanceof ApiRequestError && cause.status === 409) {
        saveErrorMessage.value =
          '保存冲突：文章标识可能已被使用，或草稿版本已变化。输入已保留，请更换标识重试；若仍失败，请重新读取最新版本。'
      } else if (cause instanceof Error) {
        saveErrorMessage.value = cause.message
      } else {
        saveErrorMessage.value = '草稿保存失败，输入已保留，请重试。'
      }
      feedback.error(saveErrorMessage.value)
    } else {
      feedback.error(cause)
    }
  } finally {
    activeOperation.value = null
  }
}
// 4. 保存提交完整编辑字段和已知版本，创建草稿成功后替换为正式编辑地址。
async function saveDraft() {
  if (isMarkdownBlocked.value) {
    feedback.error('正文尚未通过转换校验，请先完成校验或撤销无法转换的操作，再保存草稿。')
    return
  }
  if (slugIssue.value) {
    feedback.error(slugIssue.value)
    return
  }
  saveErrorMessage.value = ''
  await runEditorOperation('save', async () => {
    const updatedBlogPost = await blogApi.save(props.id, {
      ...editorForm,
      ...(savedBlogPost.value ? { version: savedBlogPost.value.version } : {}),
    })
    acceptSavedBlogPost(updatedBlogPost)
    feedback.success('草稿已保存。')
    if (props.id === null) {
      await navigateTo(`${ADMIN_PATHS.blogs}/${updatedBlogPost.id}`, { replace: true })
    }
  })
}
// 5. 发布或下架仅操作已保存快照，禁止用返回快照吞掉未保存输入。
async function changePublicationStatus(published: boolean) {
  // 状态接口返回完整快照；未保存时接收它会吞掉输入，因此发布、下架共用这个保护。
  if (
    !savedBlogPost.value ||
    isDirty.value ||
    isMarkdownBlocked.value ||
    (published && !canPublish.value)
  ) {
    return
  }
  await runEditorOperation(published ? 'publish' : 'unpublish', async () => {
    acceptSavedBlogPost(
      await blogApi.changeStatus(savedBlogPost.value!.id, savedBlogPost.value!.version, published),
    )
    feedback.success(published ? '文章已发布。' : '文章已下架。')
  })
}
// 6. 上传成功只插入稳定资源引用，实际关联仍由保存流程完成。
async function uploadResource(event: Event, usage: AssetUsage) {
  // 转换恢复区尚未确认时不能追加资源并重载正文，避免覆盖尚待修复的富文本输入。
  if (isMarkdownBlocked.value) {
    feedback.error('请先完成正文转换校验或撤销无法转换的操作，再插入资源。')
    return
  }
  const input = event.currentTarget as HTMLInputElement
  const file = input.files?.[0]
  if (!file) {
    return
  }
  await runEditorOperation(usage === 'IMAGE' ? 'image' : 'attachment', async () => {
    const asset = await assetApi.upload(file, usage)
    uploadedResources.value = [
      ...uploadedResources.value,
      {
        key: 'pending-asset-' + asset.id,
        type: usage === 'IMAGE' ? 'IMAGE' : 'ATTACHMENT',
        status: 'READY',
        assetId: asset.id,
        resourceUrl: asset.resourceUrl,
        expiresAt: asset.expiresAt,
        originalUri: null,
      },
    ]
    const name = file.name.replace(/[[\]\r\n]/g, '')
    editorForm.content +=
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
async function downloadSavedDraft() {
  // 导出的来源始终是已保存版本，不把尚未保存的输入冒充成导出结果。
  if (savedBlogPost.value && !isDirty.value) {
    await runEditorOperation('export', () =>
      blogApi.download(savedBlogPost.value!.id, includeResources.value, true),
    )
  }
}
</script>
<template>
  <div v-if="isLoading" aria-label="读取文章" class="grid gap-5">
    <USkeleton class="h-12" /><USkeleton class="h-96" />
  </div>
  <UCard v-else-if="id !== null && !savedBlogPost">
    <p class="mb-4 text-sm text-muted">文章暂时无法读取，请重试。</p>
    <UButton color="neutral" variant="outline" @click="loadBlogPost">重新读取</UButton>
  </UCard>
  <div v-else class="space-y-6" :aria-busy="isOperationPending">
    <UCollapsible>
      <UButton
        color="neutral"
        variant="outline"
        icon="i-lucide-settings-2"
        trailing-icon="i-lucide-chevron-down"
        class="max-w-full"
      >
        文章信息 · {{ editorForm.title || '未命名文章' }}
      </UButton>
      <template #content>
        <UCard class="mt-4">
          <template #header><h2 class="font-semibold">文章信息</h2></template>
          <div class="grid gap-5">
            <UFormField label="标题">
              <UInput
                v-model="editorForm.title"
                :maxlength="BLOG_INPUT_LIMITS.title"
                :disabled="isOperationPending"
                placeholder="给文章起个标题"
                class="w-full"
                size="lg"
              />
            </UFormField>
            <UFormField
              label="文章标识"
              hint="Slug"
              :error="slugIssue || undefined"
              :description="
                isSlugLocked
                  ? '首次发布后公开地址固定，下架后也不能修改文章标识。'
                  : '用于公开地址；可以留空保存草稿，首次发布前必须填写并保存。'
              "
            >
              <UInput
                id="article-slug"
                v-model="editorForm.slug"
                :disabled="isOperationPending || isSlugLocked"
                :maxlength="BLOG_INPUT_LIMITS.slug"
                placeholder="填写这篇文章的独立标识"
                class="w-full"
                size="lg"
                @update:model-value="saveErrorMessage = ''"
              />
              <p v-if="!editorForm.slug && !isSlugLocked" class="mt-2 text-sm text-muted">
                尚未设置文章标识，请在这里填写后保存草稿，再发布文章。
              </p>
              <p v-else-if="!slugIssue" class="mt-2 break-all text-xs leading-6 text-muted">
                公开地址（发布后可访问）：<code
                  >/blog/{{ encodeURIComponent(editorForm.slug) }}</code
                >
              </p>
              <p class="mt-2 text-xs leading-6 text-muted">
                文章标识独立于标题，不会随标题变化；是否重名由服务端在保存时检查。
              </p>
            </UFormField>

            <UFormField label="摘要" description="用于博客列表的文章简介，可留空。">
              <UTextarea
                v-model="editorForm.summary"
                :maxlength="BLOG_INPUT_LIMITS.summary"
                :disabled="isOperationPending"
                placeholder="这篇文章主要讲什么？"
                class="w-full"
              />
            </UFormField>
            <UCheckbox
              v-model="editorForm.remoteRightsConfirmed"
              :disabled="isOperationPending"
              label="允许保存正文中的网络图片"
              description="仅当你拥有使用权时勾选；已上传的本地资源不需要勾选。"
            />
          </div>
        </UCard>
      </template>
    </UCollapsible>
    <UAlert
      v-if="saveErrorMessage"
      role="alert"
      color="error"
      variant="soft"
      title="草稿未保存"
      :description="saveErrorMessage"
    />
    <section class="min-w-0 space-y-4" aria-label="编辑与实时预览">
      <div class="flex flex-wrap items-center justify-between gap-3">
        <div class="flex flex-wrap items-center gap-3">
          <h3 class="text-sm font-medium">正文工作台</h3>
          <span class="text-xs text-muted">{{ isDirty ? '有未保存修改' : '内容已保存' }}</span>
        </div>
        <div class="flex flex-wrap gap-2">
          <UButton
            size="sm"
            icon="i-lucide-save"
            :loading="activeOperation === 'save'"
            :disabled="isOperationPending || isMarkdownBlocked || Boolean(slugIssue)"
            @click="saveDraft"
            >保存草稿</UButton
          >
          <input
            ref="imageInput"
            type="file"
            :accept="BLOG_IMAGE_ACCEPT"
            hidden
            :disabled="isOperationPending || isMarkdownBlocked"
            aria-label="选择正文图片"
            @change="uploadResource($event, 'IMAGE')"
          />
          <input
            ref="attachmentInput"
            type="file"
            hidden
            :disabled="isOperationPending || isMarkdownBlocked"
            aria-label="选择正文附件"
            @change="uploadResource($event, 'ATTACHMENT')"
          />
          <UButton
            color="neutral"
            variant="outline"
            size="sm"
            icon="i-lucide-image-plus"
            :loading="activeOperation === 'image'"
            :disabled="isOperationPending || isMarkdownBlocked"
            @click="imageInput?.click()"
            >插入图片</UButton
          >
          <UButton
            color="neutral"
            variant="outline"
            size="sm"
            icon="i-lucide-paperclip"
            :loading="activeOperation === 'attachment'"
            :disabled="isOperationPending || isMarkdownBlocked"
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
        <ClientOnly>
          <LazyBlogEditorWorkbench
            v-model="editorForm.content"
            :disabled="isOperationPending"
            :resources="previewResources"
            @update:blocked="isMarkdownBlocked = $event"
          />
          <template #fallback>
            <UTextarea
              :model-value="editorForm.content"
              disabled
              :rows="18"
              aria-label="正在准备正文编辑器"
              class="w-full"
            />
          </template>
        </ClientOnly>
      </UFormField>
    </section>
    <div class="min-w-0 space-y-6">
      <UCard v-if="unresolvedResources.length">
        <template #header>
          <div class="flex items-center gap-3">
            <h2 class="font-semibold">待修复资源</h2>
            <UBadge color="warning" variant="subtle">{{ unresolvedResources.length }} 个</UBadge>
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
              {{ resource.errorMessage || '此历史版本未记录具体原因，请重新导入原文件查看诊断。' }}
            </p>
            <code class="mt-2 block break-all text-xs"
              >{{ DOCUMENT_RESOURCE_REFERENCE_PREFIX }}{{ resource.key }}</code
            >
          </li>
        </ul>
      </UCard>
    </div>
    <aside class="grid min-w-0 items-start gap-5 lg:grid-cols-2" aria-label="文章设置与操作">
      <UCard>
        <template #header>
          <div class="flex items-center justify-between gap-3">
            <h2 class="font-semibold">文章设置</h2>
            <UBadge
              :color="savedBlogPost?.status === 'PUBLISHED' ? 'success' : 'neutral'"
              variant="subtle"
              >{{ savedBlogPost?.status === 'PUBLISHED' ? '已发布' : '草稿' }}</UBadge
            >
          </div>
        </template>
        <div class="grid gap-5">
          <UCheckbox
            v-model="editorForm.allowDownload"
            :disabled="isOperationPending"
            label="允许登录访客下载文章"
          />
          <div class="border-t border-default pt-4 text-xs leading-6 text-muted">
            <p>
              {{ savedBlogPost ? `已保存版本 ${savedBlogPost.version}` : '尚未保存' }} ·
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
              :disabled="isOperationPending || isMarkdownBlocked || Boolean(slugIssue)"
              @click="saveDraft"
              >保存草稿</UButton
            >
            <UButton
              v-if="savedBlogPost"
              block
              color="neutral"
              variant="outline"
              icon="i-lucide-send"
              :loading="activeOperation === 'publish'"
              :disabled="isOperationPending || isMarkdownBlocked || isDirty || !canPublish"
              @click="changePublicationStatus(true)"
              >发布当前草稿</UButton
            >
            <p v-if="savedBlogPost && isDirty" class="text-xs text-muted">
              先保存修改，再发布、下架或导出。
            </p>
            <p v-if="savedBlogPost && !canPublish" class="text-xs leading-6 text-muted">
              发布前请补全文章标识、标题和正文，并保存草稿。
            </p>
            <div v-if="savedBlogPost" class="flex flex-wrap gap-2">
              <UButton
                color="neutral"
                variant="ghost"
                size="sm"
                :disabled="isOperationPending"
                @click="loadBlogPost"
                >{{ isDirty ? '放弃修改并重载' : '刷新已保存版本' }}</UButton
              >
              <UButton
                v-if="savedBlogPost.status === 'PUBLISHED'"
                color="neutral"
                variant="ghost"
                size="sm"
                :disabled="isOperationPending || isMarkdownBlocked || isDirty"
                :loading="activeOperation === 'unpublish'"
                @click="changePublicationStatus(false)"
                >下架文章</UButton
              >
              <UButton
                v-if="savedBlogPost.status === 'PUBLISHED'"
                :to="'/blog/' + savedBlogPost.slug"
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
      <UCard v-if="savedBlogPost">
        <template #header><h2 class="font-semibold">导出文章</h2></template>
        <div class="grid gap-4">
          <p class="text-xs leading-5 text-muted">
            仅导出正文为 .md，资源使用访问链接；携带图片或附件为 ZIP。
          </p>
          <UCheckbox
            v-if="savedBlogPost.resources.length"
            v-model="includeResources"
            :disabled="isOperationPending"
            label="包含图片和附件（ZIP）"
          />
          <UButton
            block
            color="neutral"
            variant="outline"
            icon="i-lucide-download"
            :loading="activeOperation === 'export'"
            :disabled="isOperationPending || isDirty"
            @click="downloadSavedDraft"
            >导出已保存草稿</UButton
          >
        </div>
      </UCard>
      <UButton :to="ADMIN_PATHS.blogs" color="neutral" variant="link" icon="i-lucide-arrow-left"
        >返回文章列表</UButton
      >
    </aside>
  </div>
</template>
