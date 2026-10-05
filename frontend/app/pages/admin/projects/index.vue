<script setup lang="ts">
import { getManagedProjects, changeProjectStatus, type AdminProject, type ProjectPage } from '../../../api/projects'
import { useAuthState } from '../../../composables/useAuthState'
import { PROJECT_MESSAGES } from '../../../constants/projects'
import { canManageProjects } from '../../../api/permissions'
definePageMeta({ middleware: 'owner' })
useSeoMeta({ title: '项目管理', robots: 'noindex,nofollow' })
const auth = useAuthState()
const result = ref<ProjectPage<AdminProject> | null>(null)
const page = ref(1)
const status = ref('')
const pending = ref(false)
const error = ref('')
async function load() {
  if (!auth.isOwner.value || pending.value) return
  pending.value = true; error.value = ''
  try { result.value = await getManagedProjects(page.value, status.value) }
  catch (cause) { error.value = cause instanceof Error ? cause.message : PROJECT_MESSAGES.loadFailed }
  finally { pending.value = false }
}
async function transition(project: AdminProject) {
  if (!auth.isOwner.value || pending.value) return
  pending.value = true; error.value = ''
  try { await changeProjectStatus(project.id, project.version, project.status === 'DRAFT') }
  catch (cause) { error.value = cause instanceof Error ? cause.message : PROJECT_MESSAGES.saveFailed }
  finally { pending.value = false }
  if (!error.value) await load()
}
async function changePage(next: number) { page.value = next; await load() }
onMounted(load)
watch(() => auth.state.value, session => {
  if (!canManageProjects(session)) result.value = null
})
</script>
<template>
  <main class="mx-auto max-w-[1200px] px-5 pb-16">
    <AdminNavigation />
    <p v-if="!auth.isOwner.value" role="status">{{ PROJECT_MESSAGES.verifying }}</p>
    <template v-else>
      <div class="mb-8 flex flex-wrap items-center justify-between gap-4">
        <h1 class="text-3xl font-bold">项目管理</h1>
        <NuxtLink to="/admin/projects/new" class="rounded-lg border border-line px-5 py-3 text-accent">新增项目</NuxtLink>
      </div>
      <label>状态筛选 <select v-model="status" :disabled="pending" class="rounded-lg border border-line bg-surface px-4 py-2" @change="page = 1; load()"><option value="">全部</option><option value="DRAFT">草稿</option><option value="PUBLISHED">已发布</option></select></label>
      <p v-if="error" role="alert" class="my-5">{{ error }}</p>
      <p v-if="pending" role="status" class="my-5">正在加载…</p>
      <p v-else-if="!result?.items.length" role="status" class="my-8">{{ PROJECT_MESSAGES.manageEmpty }}</p>
      <div v-else class="mt-6 grid gap-4">
        <article v-for="project in result.items" :key="project.id" class="flex flex-wrap items-center justify-between gap-5 rounded-2xl border border-line p-6">
          <div><h2 class="text-xl font-semibold">{{ project.title || '未命名草稿' }}</h2><p class="mt-2 text-muted">{{ project.status === 'PUBLISHED' ? '已发布' : '草稿' }} · 版本 {{ project.version }} · {{ project.featured ? '首页重点' : '普通项目' }} · 排序 {{ project.sortOrder }}</p></div>
          <div v-if="auth.isOwner.value" class="flex gap-5">
            <NuxtLink :to="'/admin/projects/' + project.id" class="text-accent">编辑项目</NuxtLink>
            <button type="button" :disabled="pending" @click="transition(project)">{{ project.status === 'PUBLISHED' ? '下架' : '发布' }}</button>
          </div>
        </article>
      </div>
      <nav v-if="result" class="mt-8 flex gap-6" aria-label="管理列表分页">
        <button v-if="page > 1" type="button" :disabled="pending" @click="changePage(page - 1)">上一页</button>
        <span>第 {{ page }} 页 · 共 {{ result.total }} 个项目</span>
        <button v-if="page * result.size < result.total" type="button" :disabled="pending" @click="changePage(page + 1)">下一页</button>
      </nav>
    </template>
  </main>
</template>
