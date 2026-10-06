<script setup lang="ts">
import { onMounted } from 'vue'
import { useProjectEditor } from '../composables/useProjectEditor'
import { ADMIN_MESSAGES, ADMIN_PATHS, ADMIN_PROJECT_LIMITS, ADMIN_PROJECT_LINK_TYPES, ADMIN_PROJECT_STATUS } from '../constants/admin'
import { isProjectDate } from '../utils/projectDate'

const props = defineProps<{ projectId?: number }>()
const { form, tags, status, previouslyPublished, loaded, operation, pending, error, notice,
  dirty, canOperate, load, reload: reloadProject, save, transition, setTag, addLink, removeLink } = useProjectEditor(props.projectId)
const linkTypes = ADMIN_PROJECT_LINK_TYPES.map(item => ({ ...item }))
const completionDate = computed({
  get: () => isProjectDate(form.timeLabel) ? form.timeLabel : '',
  set: (value: string) => { form.timeLabel = value },
})
const legacyTimeLabel = computed(() => form.timeLabel && !isProjectDate(form.timeLabel) ? form.timeLabel : '')

async function submit() {
  const savedId = await save()
  if (savedId !== null && !props.projectId) await navigateTo(ADMIN_PATHS.projects + '/' + savedId)
}

async function reload() {
  await reloadProject(() => window.confirm(ADMIN_MESSAGES.discardProjectConfirm))
}

onMounted(() => load())
</script>

<template>
  <div class="grid gap-6" :aria-busy="pending">
    <UAlert v-if="error" role="alert" color="error" variant="subtle" :title="error" icon="i-lucide-circle-alert" />
    <UAlert v-if="notice" role="status" color="success" variant="subtle" :title="notice" icon="i-lucide-circle-check" />
    <UCard v-if="!loaded">
      <div v-if="pending" role="status" :aria-label="ADMIN_MESSAGES.loadingProject" class="grid min-h-[600px] gap-6">
        <div class="grid gap-5 md:grid-cols-2"><USkeleton class="h-16 w-full" /><USkeleton class="h-16 w-full" /></div>
        <USkeleton class="h-36 w-full" /><USkeleton class="h-44 w-full" /><USkeleton class="h-12 w-64" />
      </div>
      <div v-else class="flex justify-center py-8"><UButton type="button" color="neutral" variant="outline" icon="i-lucide-refresh-cw" @click="reload">重新读取</UButton></div>
    </UCard>
    <form v-else class="grid gap-6" @submit.prevent="submit">
      <AdminEditorSection title="项目内容" description="草稿可以逐步完善；发布需填写标题、摘要、本人贡献，并选择技术标签。">
        <fieldset :disabled="pending" class="grid gap-5">
          <div class="grid gap-5 md:grid-cols-2">
            <UFormField label="项目标识（slug）" name="slug" :help="previouslyPublished ? ADMIN_MESSAGES.slugLocked : '用于项目唯一标识，建议使用简短英文与连字符。'">
              <UInput id="project-slug" v-model="form.slug" :maxlength="ADMIN_PROJECT_LIMITS.slug" :readonly="previouslyPublished" :disabled="pending" class="w-full" placeholder="my-agent-project" />
            </UFormField>
            <UFormField label="项目标题" name="title"><UInput id="project-title" v-model="form.title" :maxlength="ADMIN_PROJECT_LIMITS.title" :disabled="pending" class="w-full" /></UFormField>
          </div>
          <UFormField label="摘要" name="summary"><UTextarea id="project-summary" v-model="form.summary" :maxlength="ADMIN_PROJECT_LIMITS.content" :rows="3" :disabled="pending" class="w-full" /></UFormField>
          <UFormField label="本人贡献" name="contribution"><UTextarea id="project-contribution" v-model="form.contribution" :maxlength="ADMIN_PROJECT_LIMITS.content" :rows="4" :disabled="pending" class="w-full" /></UFormField>
          <UFormField label="成果（可选）" name="outcome"><UTextarea id="project-outcome" v-model="form.outcome" :maxlength="ADMIN_PROJECT_LIMITS.content" :rows="3" :disabled="pending" class="w-full" /></UFormField>
          <UFormField :label="ADMIN_MESSAGES.dateLabel" name="timeLabel" :help="ADMIN_MESSAGES.dateHint">
            <div class="flex flex-wrap items-center gap-3">
              <!-- 原生日历由浏览器处理本地化和键盘访问，接口仍提交 YYYY-MM-DD，不修改后端。 -->
              <UInput id="project-time" v-model="completionDate" type="date" min="0001-01-01" max="9999-12-31" :disabled="pending" class="w-full sm:w-64" />
              <UButton v-if="form.timeLabel" type="button" variant="ghost" color="neutral" :disabled="pending" @click="form.timeLabel = ''">{{ ADMIN_MESSAGES.clearDate }}</UButton>
            </div>
            <p v-if="legacyTimeLabel" class="mt-2 text-sm text-muted">{{ ADMIN_MESSAGES.legacyDateHint }}{{ legacyTimeLabel }}</p>
          </UFormField>
        </fieldset>
      </AdminEditorSection>
      <AdminEditorSection title="技术与展示" description="标签体现实际使用的技术；首页重点与排序只影响公开展示。">
        <fieldset :disabled="pending" class="grid gap-6">
          <fieldset>
            <legend class="mb-3 text-sm font-medium">技术标签</legend>
            <div v-if="tags.length" class="flex flex-wrap gap-x-6 gap-y-4">
              <UCheckbox v-for="tag in tags" :key="tag.id" :model-value="form.tagIds.includes(tag.id)" :data-tag-id="tag.id" :label="tag.name" :disabled="pending" @update:model-value="setTag(tag.id, $event === true)" />
            </div>
            <p v-else class="text-sm text-muted">{{ ADMIN_MESSAGES.tagsEmpty }}</p>
          </fieldset>
          <div class="grid items-start gap-5 sm:grid-cols-2">
            <UCheckbox id="project-featured" v-model="form.featured" label="首页重点" description="纳入首页重点项目展示，首页仍最多展示四张卡片。" :disabled="pending" />
            <UFormField label="排序（越小越靠前）" name="sortOrder"><UInput id="project-sort" v-model.number="form.sortOrder" type="number" :min="0" :step="1" :disabled="pending" class="w-full" /></UFormField>
          </div>
        </fieldset>
      </AdminEditorSection>
      <AdminEditorSection title="外部入口（可选）" description="仅填写真实 HTTP(S) 链接，关闭展示后访客不会看到该入口。">
        <fieldset :disabled="pending" class="grid gap-4">
          <p v-if="!form.links.length" class="text-sm text-muted">{{ ADMIN_MESSAGES.linksEmpty }}</p>
          <div v-for="(link, index) in form.links" :key="index" class="grid gap-4 rounded-xl border border-line p-4 sm:p-5">
            <div class="flex items-center justify-between gap-3"><h3 class="text-sm font-semibold">外部入口 {{ index + 1 }}</h3><UButton type="button" color="error" variant="ghost" size="sm" icon="i-lucide-trash-2" :disabled="pending" :aria-label="'移除外部入口 ' + (index + 1)" @click="removeLink(index)">移除入口</UButton></div>
            <div class="grid gap-4 sm:grid-cols-2">
              <UFormField label="入口类型" :name="'links.' + index + '.type'"><USelect v-model="link.type" :items="linkTypes" :disabled="pending" class="w-full" /></UFormField>
              <UFormField label="名称" :name="'links.' + index + '.label'"><UInput :model-value="link.label ?? ''" :maxlength="ADMIN_PROJECT_LIMITS.linkLabel" :disabled="pending" class="w-full" @update:model-value="link.label = $event" /></UFormField>
            </div>
            <UFormField label="地址" :name="'links.' + index + '.url'"><UInput v-model="link.url" type="url" :maxlength="ADMIN_PROJECT_LIMITS.linkUrl" placeholder="https://" :disabled="pending" class="w-full" /></UFormField>
            <div class="grid items-center gap-4 sm:grid-cols-2">
              <UFormField label="外链排序" :name="'links.' + index + '.sortOrder'"><UInput v-model.number="link.sortOrder" type="number" :min="0" :step="1" :disabled="pending" class="w-full" /></UFormField>
              <UCheckbox :model-value="link.visible === true" label="向访客展示" :disabled="pending" @update:model-value="link.visible = $event === true" />
            </div>
          </div>
          <UButton type="button" color="neutral" variant="outline" icon="i-lucide-plus" class="w-fit" :disabled="pending" @click="addLink">添加外部入口</UButton>
        </fieldset>
      </AdminEditorSection>
      <UCard>
        <div class="flex flex-wrap items-center justify-between gap-4">
          <div class="flex flex-wrap items-center gap-2"><UBadge :color="status === ADMIN_PROJECT_STATUS.PUBLISHED ? 'success' : 'neutral'" variant="subtle">{{ status === ADMIN_PROJECT_STATUS.PUBLISHED ? '已发布' : '草稿' }}</UBadge><span class="text-sm text-muted">版本 {{ form.version ?? '未保存' }}</span></div>
          <div class="flex flex-wrap gap-3">
            <UButton type="submit" icon="i-lucide-save" :disabled="!canOperate" :loading="operation === 'save'">保存项目</UButton>
            <UButton v-if="projectId" type="button" color="neutral" variant="outline" :disabled="!canOperate || dirty" :loading="operation === 'publish' || operation === 'unpublish'" @click="transition(status === ADMIN_PROJECT_STATUS.DRAFT)">{{ status === ADMIN_PROJECT_STATUS.DRAFT ? '发布项目' : '下架项目' }}</UButton>
            <UButton v-if="projectId" type="button" color="neutral" variant="ghost" icon="i-lucide-refresh-cw" :disabled="pending" :loading="operation === 'load'" @click="reload">重新读取</UButton>
          </div>
        </div>
        <p role="status" class="mt-4 text-sm leading-6 text-muted">{{ dirty ? ADMIN_MESSAGES.projectDirty : projectId ? ADMIN_MESSAGES.projectClean : ADMIN_MESSAGES.newProjectClean }}</p>
      </UCard>
    </form>
  </div>
</template>
