<script setup lang="ts">
import { markRaw, shallowRef } from 'vue'
import { useBlogApi, type Blog, type BlogTag, type DocumentResource } from '../api/blogs'
import { useAssetApi } from '../api/assets'
import { useAssetUrlRefresh } from '../composables/useAssetUrlRefresh'
import { createImmersiveValidator } from '../utils/editor/immersiveValidationClient'
import { createBlogPersistence } from '../utils/editor/blogPersistence'
import { createCanvasResources } from '../utils/editor/canvasResources'
import type { CanvasMetadata } from '../utils/editor/canvasSave'
import type { JSONContent } from '@tiptap/core'
import { ADMIN_PATHS } from '../constants/admin'

const props = defineProps<{ id: number | null }>()
const api = useBlogApi()
const assets = useAssetApi()
const feedback = useFeedback()
const importer = createImmersiveValidator()
const workbench = useTemplateRef('workbench')
const metadata = ref<CanvasMetadata>({
  title: '',
  slug: '',
  summary: '',
  allowDownload: true,
  remoteRightsConfirmed: false,
  tagIds: [],
})
const resources = shallowRef<DocumentResource[]>([])
const uploaded = shallowRef<DocumentResource[]>([])
const tags = shallowRef<BlogTag[]>([])
const initialContent = shallowRef<JSONContent>()
const session = shallowRef<ReturnType<typeof createBlogPersistence>>()
const persistence = shallowRef<{
  adapter: ReturnType<typeof createBlogPersistence>['adapter']
  resources: ReturnType<typeof createCanvasResources>
}>()
const loading = ref(true)
const loadError = ref('')
const documentKey = ref(0)
const savedPost = computed(() => session.value?.post.value)
const previewResources = computed(() => [...resources.value, ...uploaded.value])
const reloadOpen = ref(false)
const publication = ref<'publish' | 'unpublish' | 'restore'>('publish')
const publicationOpen = ref(false)
function askPublication(action: typeof publication.value) {
  publication.value = action
  publicationOpen.value = true
}
let generation = 0
let disposed = false

function received(post: Blog) {
  resources.value = post.resources
  workbench.value?.acceptResources(post.resources)
  if (post.previewIssue || !post.resourcesReady) {
    feedback.info(post.previewIssue || '正文已保存，部分资源尚未就绪。')
  }
  // new 与 id 共用同一路由组件；只替换 URL，不卸载画布、不清空保存期间的新输入。
  if (props.id === null && !disposed) {
    void navigateTo(`${ADMIN_PATHS.blogs}/${post.id}`, { replace: true })
  }
}
async function load() {
  const current = ++generation
  loading.value = true
  loadError.value = ''
  try {
    const post = props.id === null ? null : await api.get(props.id)
    if (
      post &&
      (post.revision === null || !Number.isSafeInteger(post.revision) || post.revision < 1)
    ) {
      throw new Error('管理修订信息缺失，无法安全保存。请重新读取。')
    }
    const result = await importer.check({
      kind: 'canvas-import',
      markdown: post?.content ?? '',
      resources: post?.resources ?? [],
    })
    if (result.kind !== 'canvas-import') {
      throw new Error('正文加载结果不匹配。')
    }
    if (disposed || current !== generation) {
      return
    }
    metadata.value = {
      title: post?.title ?? '',
      slug: post?.slug ?? '',
      summary: post?.summary ?? '',
      allowDownload: post?.allowDownload ?? true,
      remoteRightsConfirmed: false,
      tagIds: post?.tags.map((tag) => tag.id) ?? [],
    }
    resources.value = post?.resources ?? []
    uploaded.value = []
    persistence.value?.resources.dispose()
    const local = createCanvasResources(
      () => false,
      async (file, image) => {
        const asset = await assets.upload(file, image ? 'IMAGE' : 'ATTACHMENT')
        if (disposed || current !== generation) {
          throw new Error('编辑会话已经关闭。')
        }
        uploaded.value = [
          ...uploaded.value,
          {
            key: 'pending-asset-' + asset.id,
            type: image ? 'IMAGE' : 'ATTACHMENT',
            status: 'READY',
            assetId: asset.id,
            resourceUrl: asset.resourceUrl,
            expiresAt: asset.expiresAt,
            originalUri: null,
            filename: file.name,
            contentType: asset.contentType,
          },
        ]
        return 'asset://' + asset.id
      },
    )
    session.value = markRaw(createBlogPersistence(api, post, received))
    persistence.value = markRaw({ adapter: session.value.adapter, resources: local })
    initialContent.value = markRaw(result.document)
    documentKey.value++
    // 标签读取失败不清空正文中的既有标签；保留当前标签并允许重试目录。
    tags.value = post?.tags ?? []
    void loadTags(current)
  } catch (cause) {
    if (!disposed && current === generation) {
      loadError.value = cause instanceof Error ? cause.message : '文章读取失败，请重试。'
    }
  } finally {
    if (!disposed && current === generation) {
      loading.value = false
    }
  }
}
async function loadTags(current = generation) {
  try {
    const result = await api.tags()
    if (!disposed && current === generation) {
      const existing = savedPost.value?.tags ?? []
      tags.value = [...new Map([...existing, ...result].map((tag) => [tag.id, tag])).values()]
    }
  } catch (cause) {
    if (!disposed && current === generation) {
      feedback.error(cause)
    }
  }
}
onMounted(load)
watch(
  () => props.id,
  (id) => {
    if (id !== session.value?.post.value?.id) {
      void load()
    }
  },
)
onBeforeRouteUpdate(async (to) => {
  if (to.params.id === String(savedPost.value?.id)) {
    return true
  }
  return (await workbench.value?.requestLeave()) ?? true
})
useAssetUrlRefresh(
  () => savedPost.value,
  async () => {
    const post = savedPost.value
    if (!post || session.value?.busy.value) {
      return
    }
    const latest = await api.get(post.id)
    if (disposed || savedPost.value !== post) {
      return
    }
    if (latest.revision === post.revision && latest.contentId === post.contentId) {
      resources.value = latest.resources
      session.value!.post.value = {
        ...post,
        resources: latest.resources,
        previewIssue: latest.previewIssue,
      }
    } else {
      feedback.info('服务器内容已变化。本地输入仍保留，保存前请核对最新状态。')
    }
  },
)
async function reload() {
  reloadOpen.value = false
  if (workbench.value && !(await workbench.value.requestLeave())) {
    return
  }
  await load()
}
async function confirmPublication() {
  const action = publication.value
  publicationOpen.value = false
  if (!action || !session.value || workbench.value?.isDirty() || workbench.value?.isSaving()) {
    return
  }
  try {
    await session.value.transition(action)
    const messages = {
      publish: '草稿已发布。',
      restore: '已恢复最近正式正文，草稿未发布。',
      unpublish: '文章已下架。',
    }
    feedback.success(messages[action])
  } catch (cause) {
    feedback.error(cause)
  }
}
async function downloadSaved() {
  if (!savedPost.value || workbench.value?.isDirty() || workbench.value?.isSaving()) {
    return
  }
  try {
    await api.download(savedPost.value.id, false, true)
  } catch (cause) {
    feedback.error(cause)
  }
}
onBeforeUnmount(() => {
  disposed = true
  generation++
  importer.dispose()
  persistence.value?.resources.dispose()
})
</script>
<template>
  <section class="blog-editor">
    <EditorCanvasLoading v-if="loading" phase="正在读取文章" />
    <UCard v-else-if="loadError" role="alert">
      <p class="mb-4">{{ loadError }}</p>
      <UButton @click="load">重新读取</UButton>
    </UCard>
    <div v-else class="flex min-h-0 flex-1 flex-col">
      <UAlert
        v-if="savedPost && !savedPost.resourcesReady"
        color="warning"
        title="正文已保存，部分资源尚未就绪"
        :description="
          savedPost.previewIssue ||
          savedPost.resources
            .filter((resource) => resource.status !== 'READY')
            .map((resource) => resource.errorMessage || resource.filename || resource.key)
            .join('；')
        "
      />
      <UAlert
        v-if="session?.conflicted.value"
        color="warning"
        title="服务器内容发生冲突"
        description="本地输入已保留。可先导出当前 Markdown，再重新读取服务器内容进行核对。"
      />
      <ClientOnly>
        <LazyEditorImmersiveWorkbench
          v-if="initialContent && persistence"
          :key="documentKey"
          ref="workbench"
          v-model:metadata="metadata"
          :initial-content="initialContent"
          :resources="previewResources"
          :persistence="persistence"
          :unsaved="!savedPost"
          :slug-locked="savedPost?.hasPublished"
          :tags="tags"
          :state-label="
            savedPost?.hasDraft
              ? '服务器草稿'
              : savedPost?.hasPublished
                ? '正式正文 · 编辑后保存为草稿'
                : '新建草稿'
          "
        >
          <template #actions="{ dirty, saving, ready }">
            <UButton
              v-if="savedPost?.hasDraft"
              size="sm"
              :disabled="
                dirty ||
                saving ||
                !ready ||
                session?.busy.value ||
                session?.conflicted.value ||
                !savedPost.resourcesReady ||
                !savedPost.title.trim() ||
                !savedPost.slug ||
                !savedPost.content.trim()
              "
              @click="askPublication('publish')"
              >发布草稿</UButton
            >
            <UDropdownMenu
              :items="[
                { label: '返回文章列表', to: ADMIN_PATHS.blogs },
                {
                  label: '查看公开文章',
                  to: savedPost?.status === 'PUBLISHED' ? '/blog/' + savedPost.slug : undefined,
                  disabled: savedPost?.status !== 'PUBLISHED',
                },
                {
                  label: '导出服务器已保存正文',
                  disabled: !savedPost || dirty || saving,
                  onSelect: downloadSaved,
                },
                {
                  label: '重新读取服务器内容',
                  disabled: saving || session?.busy.value,
                  onSelect: () => {
                    reloadOpen = true
                  },
                },
                { label: '重新读取标签目录', onSelect: () => loadTags() },
                {
                  label: '下架文章',
                  disabled:
                    savedPost?.status !== 'PUBLISHED' || dirty || saving || session?.busy.value,
                  onSelect: () => {
                    askPublication('unpublish')
                  },
                },
                {
                  label: '恢复最近正式正文',
                  disabled:
                    !savedPost?.hasPublished ||
                    savedPost.status === 'PUBLISHED' ||
                    dirty ||
                    saving ||
                    session?.busy.value,
                  onSelect: () => {
                    askPublication('restore')
                  },
                },
              ]"
            >
              <UButton
                icon="i-lucide-ellipsis"
                color="neutral"
                variant="ghost"
                aria-label="文章操作"
              />
            </UDropdownMenu>
          </template>
        </LazyEditorImmersiveWorkbench>
        <template #fallback><EditorCanvasLoading /></template>
      </ClientOnly>
    </div>
    <UModal
      v-model:open="publicationOpen"
      :title="
        publication === 'publish'
          ? '发布已保存草稿？'
          : publication === 'restore'
            ? '恢复最近正式正文？'
            : '下架文章？'
      "
      :description="
        publication === 'publish'
          ? '将已保存草稿替换为公开正文，发布前请确认标题、地址和内容。'
          : publication === 'restore'
            ? '恢复公开入口，保留工作草稿，不发布草稿中的修改。'
            : '关闭公开入口，保留正式正文和工作草稿。'
      "
    >
      <template #footer
        ><UButton color="neutral" variant="ghost" @click="publicationOpen = false">取消</UButton
        ><UButton @click="confirmPublication">确认</UButton></template
      >
    </UModal>
    <UModal
      v-model:open="reloadOpen"
      title="重新读取服务器内容？"
      description="先处理当前未保存输入，再读取服务器最新内容。"
    >
      <template #footer
        ><UButton color="neutral" variant="ghost" @click="reloadOpen = false">取消</UButton
        ><UButton @click="reload">继续</UButton></template
      >
    </UModal>
  </section>
</template>

<style scoped>
.blog-editor {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}
</style>
