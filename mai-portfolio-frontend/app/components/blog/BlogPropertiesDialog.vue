<script setup lang="ts">
import { useBlogApi, type BlogTag } from '../../api/blogs'
import { createBlogPersistence } from '../../utils/editor/session/blogPersistence'
import type { CanvasMetadata } from '../../utils/editor/session/types'
import {
  sameBlogProperties,
  validateBlogProperties,
  type BlogProperties,
} from '../../utils/blog/properties'
const props = defineProps<{ articleId: number }>()
const emit = defineEmits<{ close: []; saved: [] }>()
const api = useBlogApi()
const feedback = useFeedback()
const session = shallowRef<ReturnType<typeof createBlogPersistence>>()
const tags = ref<BlogTag[]>([])
const loading = ref(true)
const error = ref('')
const fields = reactive<CanvasMetadata & BlogProperties>({
  title: '',
  slug: '',
  summary: '',
  tagIds: [],
  allowDownload: true,
  remoteRightsConfirmed: false,
})
const baseline = shallowRef<BlogProperties>()
const dirty = computed(() => !!baseline.value && !sameBlogProperties(fields, baseline.value))
const saving = computed(() => session.value?.busy.value === true)
const pending = computed(() => loading.value || saving.value)
const { leaveOpen, requestLeave, decideLeave } = useAdminFormLeave(dirty, saving)
let disposed = false
function accept() {
  const post = session.value?.post.value
  if (!post) {
    return
  }
  Object.assign(fields, {
    title: post.title,
    slug: post.slug ?? '',
    summary: post.summary,
    tagIds: post.tags.map((tag) => tag.id),
    allowDownload: post.allowDownload,
  })
  baseline.value = { ...fields, tagIds: [...fields.tagIds] }
}
async function load() {
  loading.value = true
  error.value = ''
  try {
    const [post, directory] = await Promise.all([api.get(props.articleId), api.tags()])
    if (disposed) {
      return
    }
    session.value = markRaw(createBlogPersistence(api, post, accept))
    tags.value = [...new Map([...post.tags, ...directory].map((tag) => [tag.id, tag])).values()]
    accept()
  } catch (cause) {
    if (!disposed) {
      error.value = cause instanceof Error ? cause.message : '文章属性读取失败。'
    }
  } finally {
    if (!disposed) {
      loading.value = false
    }
  }
}
async function reload() {
  if (await requestLeave()) {
    await load()
  }
}
async function close() {
  if (await requestLeave()) {
    emit('close')
  }
}
async function save() {
  if (!session.value || pending.value || !dirty.value) {
    return
  }
  error.value = ''
  try {
    await session.value.saveProperties({ ...fields, tagIds: [...fields.tagIds] })
    if (disposed) {
      return
    }
    feedback.success('文章属性已保存，正文未修改。')
    emit('saved')
    emit('close')
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '属性未保存，请重试。'
  }
}
onMounted(load)
onBeforeUnmount(() => {
  disposed = true
})
</script>
<template>
  <UModal
    :open="true"
    title="文章属性"
    description="属性单独保存，不修改文章正文。"
    :dismissible="!saving"
    :close="!saving"
    :ui="{ content: 'sm:max-w-2xl', footer: 'justify-end' }"
    @update:open="!$event && close()"
  >
    <template #body>
      <div v-if="loading" role="status" aria-label="正在读取文章属性" class="space-y-5">
        <USkeleton v-for="i in 4" :key="i" class="h-16 w-full" />
      </div>
      <template v-else>
        <UAlert
          v-if="error"
          color="error"
          title="文章属性未保存"
          :description="error"
          class="mb-5"
        />
        <UForm
          v-if="session"
          id="blog-list-properties"
          :state="fields"
          :validate="(state) => validateBlogProperties(state, baseline?.slug ?? '')"
          @submit="save"
        >
          <BlogPropertiesFields
            :model-value="fields"
            :tags="tags"
            :slug-locked="session.post.value?.hasPublished"
            :disabled="pending"
            @update:model-value="Object.assign(fields, $event)"
          />
        </UForm>
        <UButton v-else color="neutral" variant="outline" @click="load">重新读取</UButton>
      </template>
    </template>
    <template #footer>
      <UButton color="neutral" variant="ghost" :disabled="saving" @click="close">取消</UButton>
      <UButton v-if="session?.conflicted.value" color="neutral" variant="outline" @click="reload"
        >重新读取</UButton
      >
      <UButton
        type="submit"
        form="blog-list-properties"
        :disabled="pending || !dirty || session?.conflicted.value"
        :loading="saving"
        >保存属性</UButton
      >
    </template>
  </UModal>
  <AdminDiscardChanges :open="leaveOpen" @decision="decideLeave" />
</template>
