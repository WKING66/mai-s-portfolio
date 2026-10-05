<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useProjectApi, type ProjectInput, type ProjectTag } from '../api/projects'
import { PROJECT_MESSAGES } from '../constants/projects'

const props = defineProps<{ projectId?: number }>()
const { getProject, getProjectTags, saveProject, changeProjectStatus } = useProjectApi()
const form = reactive<ProjectInput>({
  slug: '', title: '', summary: '', contribution: '', outcome: '', timeLabel: '',
  tagIds: [], links: [], featured: false, sortOrder: 0,
})
const tags = ref<ProjectTag[]>([])
const status = ref<'DRAFT' | 'PUBLISHED'>('DRAFT')
const previouslyPublished = ref(false)
const loaded = ref(false)
const pending = ref(false)
const error = ref('')
const notice = ref('')
const savedForm = ref('')
const dirty = computed(() => JSON.stringify(form) !== savedForm.value)
// 这里只判断表单是否可操作；访问权限交给父级 Auth 和请求拦截器。
const canOperate = computed(() => loaded.value && !pending.value)

onMounted(async () => {
  try {
    tags.value = await getProjectTags()
    if (props.projectId) apply(await getProject(props.projectId))
    loaded.value = true
  } catch (cause) { error.value = cause instanceof Error ? cause.message : PROJECT_MESSAGES.loadFailed }
})
function apply(project: Awaited<ReturnType<typeof getProject>>) {
  // 回填完整快照与版本，409 时不会覆盖用户未保存的输入。
  Object.assign(form, {
    version: project.version, slug: project.slug || '', title: project.title || '',
    summary: project.summary || '', contribution: project.contribution || '', outcome: project.outcome || '',
    timeLabel: project.timeLabel || '', tagIds: project.tags.map(tag => tag.id),
    links: project.links.map(link => ({ ...link })), featured: project.featured, sortOrder: project.sortOrder,
  })
  status.value = project.status
  previouslyPublished.value = project.publishedAt !== null
  savedForm.value = JSON.stringify(form)
}
async function save() {
  if (!canOperate.value) return
  pending.value = true; error.value = ''; notice.value = ''
  try {
    const project = await saveProject(props.projectId || null, { ...form })
    apply(project)
    notice.value = PROJECT_MESSAGES.saved
    if (!props.projectId) await navigateTo('/admin/projects/' + project.id)
  } catch (cause) { error.value = cause instanceof Error ? cause.message : PROJECT_MESSAGES.saveFailed }
  finally { pending.value = false }
}
async function transition(publish: boolean) {
  // 发布/下架只能针对已保存内容；这属于业务限制，不是鉴权规则。
  if (!canOperate.value || dirty.value || !props.projectId || form.version === undefined) return
  pending.value = true; error.value = ''; notice.value = ''
  try {
    apply(await changeProjectStatus(props.projectId, form.version, publish))
    notice.value = publish ? PROJECT_MESSAGES.published : PROJECT_MESSAGES.unpublished
  } catch (cause) { error.value = cause instanceof Error ? cause.message : PROJECT_MESSAGES.saveFailed }
  finally { pending.value = false }
}
async function reload() {
  if (!props.projectId || !canOperate.value) return
  pending.value = true
  try { apply(await getProject(props.projectId)); error.value = '' }
  catch (cause) { error.value = cause instanceof Error ? cause.message : PROJECT_MESSAGES.loadFailed }
  finally { pending.value = false }
}
function addLink() {
  if (!canOperate.value) return
  form.links.push({ type: 'CODE', label: '', url: '', visible: true, sortOrder: form.links.length })
}
</script>

<template>
  <main class="mx-auto max-w-4xl px-5 pb-16">
    <AdminNavigation />
      <h1 class="mb-6 text-3xl font-bold">{{ projectId ? '编辑项目' : '新增项目' }}</h1>
      <p v-if="error" role="alert" class="mb-5 rounded-xl border border-red-500/60 p-4">{{ error }}</p>
      <p v-if="notice" role="status" class="mb-5 text-accent">{{ notice }}</p>
      <form v-if="loaded" class="grid gap-5" @submit.prevent="save">
        <fieldset :disabled="pending" class="grid gap-5">
          <label>项目标识（slug）<input id="project-slug" v-model="form.slug" maxlength="160" :readonly="previouslyPublished" class="field" placeholder="my-agent-project"></label>
          <label>项目标题<input id="project-title" v-model="form.title" maxlength="200" class="field"></label>
          <label>摘要<textarea id="project-summary" v-model="form.summary" maxlength="16000" rows="3" class="field" /></label>
          <label>本人贡献<textarea id="project-contribution" v-model="form.contribution" maxlength="16000" rows="4" class="field" /></label>
          <label>成果（可选）<textarea id="project-outcome" v-model="form.outcome" maxlength="16000" rows="3" class="field" /></label>
          <label>展示时间（可选）<input id="project-time" v-model="form.timeLabel" maxlength="100" class="field"></label>
          <fieldset class="rounded-xl border border-line p-5">
            <legend class="px-2 font-semibold">技术标签</legend>
            <div class="flex flex-wrap gap-4">
              <label v-for="tag in tags" :key="tag.id" class="flex items-center gap-2">
                <input v-model="form.tagIds" type="checkbox" :value="tag.id" :data-tag-id="tag.id">{{ tag.name }}
              </label>
            </div>
          </fieldset>
          <div class="flex flex-wrap items-center gap-6">
            <label><input id="project-featured" v-model="form.featured" type="checkbox"> 首页重点</label>
            <label>排序（越小越靠前）<input id="project-sort" v-model.number="form.sortOrder" type="number" min="0" step="1" class="field"></label>
          </div>
          <fieldset class="grid gap-4 rounded-xl border border-line p-5">
            <legend class="px-2 font-semibold">外部入口（可选）</legend>
            <div v-for="(link, index) in form.links" :key="index" class="grid gap-3 rounded-lg border border-line p-4">
              <label>入口类型<select v-model="link.type" class="field"><option value="CODE">代码仓库</option><option value="DEMO">在线演示</option><option value="DOCUMENTATION">文档</option><option value="OTHER">其他</option></select></label>
              <label>名称<input v-model="link.label" maxlength="100" class="field"></label>
              <label>地址<input v-model="link.url" type="url" maxlength="2048" class="field" placeholder="https://"></label>
              <label>外链排序<input v-model.number="link.sortOrder" type="number" min="0" class="field"></label>
              <label><input v-model="link.visible" type="checkbox"> 向访客展示</label>
              <button type="button" class="action" @click="form.links.splice(index, 1)">移除入口</button>
            </div>
            <button type="button" class="action w-fit" @click="addLink">添加外部入口</button>
          </fieldset>
        </fieldset>
        <div class="flex flex-wrap gap-4">
          <button class="action bg-accent/15" type="submit" :disabled="!canOperate">{{ pending ? '处理中…' : '保存项目' }}</button>
          <button v-if="projectId && status === 'DRAFT'" class="action" type="button" :disabled="!canOperate || dirty" @click="transition(true)">发布项目</button>
          <button v-if="projectId && status === 'PUBLISHED'" class="action" type="button" :disabled="!canOperate || dirty" @click="transition(false)">下架项目</button>
          <button v-if="projectId" class="action" type="button" :disabled="!canOperate" @click="reload">重新读取</button>
          <NuxtLink to="/admin/projects" class="action">返回列表</NuxtLink>
        </div>
        <p class="text-sm text-muted">当前状态：{{ status === 'PUBLISHED' ? '已发布（保存后立即更新公开卡片）' : '草稿' }} · 版本 {{ form.version ?? '未保存' }}。发布和下架针对已保存内容；修改后请先保存。</p>
      </form>
  </main>
</template>
<style scoped>
.field { display: block; width: 100%; margin-top: .5rem; padding: .75rem; border: 1px solid var(--site-line); border-radius: .6rem; background: var(--site-surface); color: var(--site-text); }
.action { padding: .65rem 1rem; border: 1px solid var(--site-line); border-radius: .6rem; }
button:disabled { opacity: .5; cursor: not-allowed; }
</style>
