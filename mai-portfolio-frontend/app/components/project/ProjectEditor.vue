<script setup lang="ts">
import { onMounted } from 'vue'
import { useProjectEditor } from '../../composables/useProjectEditor'
import {
  ADMIN_MESSAGES,
  ADMIN_PROJECT_LIMITS,
  ADMIN_PROJECT_LINK_TYPES,
  ADMIN_PROJECT_STATUS,
} from '../../constants/admin'
import { isProjectDate } from '../../utils/project/date'
import { PROJECT_MESSAGES } from '../../constants/projects'

const emit = defineEmits<{ saved: [id: number]; cancel: [] }>()
const props = withDefaults(defineProps<{ projectId?: number }>(), { projectId: undefined })
const {
  form: projectForm,
  tags: projectTags,
  status: projectStatus,
  previouslyPublished: hasBeenPublished,
  loaded: isLoaded,
  operation: activeOperation,
  pending: isPending,
  dirty: isDirty,
  canOperate,
  error: projectError,
  load,
  save,
  transition,
  addLink,
  removeLink,
  coverPreview,
  uploadCover,
  removeCover,
} = useProjectEditor(props.projectId)
const blocking = computed(() => isPending.value && activeOperation.value !== 'load')
const { leaveOpen, requestLeave, decideLeave } = useAdminFormLeave(isDirty, blocking)
defineExpose({ pending: blocking, requestLeave })
// 1. 原生文件输入仅负责选择，上传和资源关联继续复用已有编辑状态管理。
async function uploadSelectedCover(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (file) {
    await uploadCover(file)
  }
  input.value = '' // 同一文件失败后也允许重新选择。
}
const linkTypes = ADMIN_PROJECT_LINK_TYPES.map((item) => ({ ...item }))
const completionDate = computed({
  get: () => (isProjectDate(projectForm.timeLabel) ? projectForm.timeLabel : ''),
  set: (value: string) => {
    projectForm.timeLabel = value
  },
})
const legacyTimeLabel = computed(() =>
  projectForm.timeLabel && !isProjectDate(projectForm.timeLabel) ? projectForm.timeLabel : '',
)

// 2. 保存成功才通知弹窗关闭；失败保留原表单，列表继续展示已保存快照。
async function saveProjectAndNavigate() {
  const savedId = await save()
  if (savedId !== null) {
    emit('saved', savedId)
  }
}

// 3. 手动重读沿用未保存确认，初次挂载读取不触发放弃输入流程。
async function reloadProjectWithConfirmation() {
  if (await requestLeave()) {
    await load(true)
  }
}

const saveStatusMessage = computed(() => {
  if (isDirty.value) {
    return ADMIN_MESSAGES.projectDirty
  }
  return props.projectId ? ADMIN_MESSAGES.projectClean : ADMIN_MESSAGES.newProjectClean
})

onMounted(() => load())
</script>

<template>
  <div class="flex min-h-0 flex-1 flex-col" :aria-busy="isPending">
    <UCard v-if="!isLoaded" class="overflow-y-auto"
      ><UAlert
        v-if="projectError"
        color="error"
        title="项目读取失败"
        :description="projectError"
        class="mb-4"
      />
      <div
        v-if="isPending"
        role="status"
        :aria-label="ADMIN_MESSAGES.loadingProject"
        class="grid min-h-64 gap-6"
      >
        <div class="grid gap-5">
          <USkeleton class="h-16 w-full" /><USkeleton class="h-16 w-full" />
        </div>
        <USkeleton class="h-36 w-full" /><USkeleton class="h-44 w-full" /><USkeleton
          class="h-12 w-64"
        />
      </div>
      <div v-else class="flex justify-center py-8">
        <UButton
          type="button"
          color="neutral"
          variant="outline"
          icon="i-lucide-refresh-cw"
          @click="reloadProjectWithConfirmation"
          >重新读取</UButton
        >
      </div>
    </UCard>
    <form v-else class="flex min-h-0 flex-1 flex-col" @submit.prevent="saveProjectAndNavigate">
      <div class="min-h-0 flex-1 space-y-7 overflow-y-auto p-5 sm:p-6">
        <UAlert
          v-if="projectError"
          color="error"
          title="项目操作未完成"
          :description="projectError"
        />
        <AdminEditorSection
          plain
          title="项目内容"
          description="草稿可以逐步完善；发布需填写标题、摘要、本人贡献，并选择技术标签。"
        >
          <fieldset :disabled="isPending" class="grid gap-5">
            <div class="grid gap-5">
              <UFormField
                label="项目标识（slug）"
                name="slug"
                :help="
                  hasBeenPublished
                    ? ADMIN_MESSAGES.slugLocked
                    : '用于项目唯一标识，建议使用简短英文与连字符。'
                "
              >
                <UInput
                  id="project-slug"
                  v-model="projectForm.slug"
                  :maxlength="ADMIN_PROJECT_LIMITS.slug"
                  :readonly="hasBeenPublished"
                  :disabled="isPending"
                  class="w-full"
                  placeholder="my-agent-project"
                />
              </UFormField>
              <UFormField label="项目标题" name="title"
                ><UInput
                  id="project-title"
                  v-model="projectForm.title"
                  :maxlength="ADMIN_PROJECT_LIMITS.title"
                  :disabled="isPending"
                  class="w-full"
              /></UFormField>
            </div>
            <UFormField label="摘要" name="summary"
              ><UTextarea
                id="project-summary"
                v-model="projectForm.summary"
                :maxlength="ADMIN_PROJECT_LIMITS.content"
                :rows="3"
                :disabled="isPending"
                class="w-full"
            /></UFormField>
            <UFormField label="本人贡献" name="contribution"
              ><UTextarea
                id="project-contribution"
                v-model="projectForm.contribution"
                :maxlength="ADMIN_PROJECT_LIMITS.content"
                :rows="4"
                :disabled="isPending"
                class="w-full"
            /></UFormField>
            <UFormField label="成果（可选）" name="outcome"
              ><UTextarea
                id="project-outcome"
                v-model="projectForm.outcome"
                :maxlength="ADMIN_PROJECT_LIMITS.content"
                :rows="3"
                :disabled="isPending"
                class="w-full"
            /></UFormField>
            <UFormField
              :label="ADMIN_MESSAGES.dateLabel"
              name="timeLabel"
              :help="ADMIN_MESSAGES.dateHint"
            >
              <div class="flex flex-wrap items-center gap-3">
                <!-- 原生日历由浏览器处理本地化和键盘访问，接口仍提交 YYYY-MM-DD，不修改后端。 -->
                <UInput
                  id="project-time"
                  v-model="completionDate"
                  type="date"
                  min="0001-01-01"
                  max="9999-12-31"
                  :disabled="isPending"
                  class="w-full sm:w-64"
                />
                <UButton
                  v-if="projectForm.timeLabel"
                  type="button"
                  variant="ghost"
                  color="neutral"
                  :disabled="isPending"
                  @click="projectForm.timeLabel = ''"
                  >{{ ADMIN_MESSAGES.clearDate }}</UButton
                >
              </div>
              <p v-if="legacyTimeLabel" class="mt-2 text-sm text-muted">
                {{ ADMIN_MESSAGES.legacyDateHint }}{{ legacyTimeLabel }}
              </p>
            </UFormField>
          </fieldset>
        </AdminEditorSection>
        <AdminEditorSection
          plain
          :title="PROJECT_MESSAGES.coverLabel"
          :description="PROJECT_MESSAGES.coverHint"
        >
          <div class="grid gap-4">
            <img
              v-if="coverPreview"
              :src="coverPreview"
              :alt="projectForm.title || PROJECT_MESSAGES.coverLabel"
              class="max-h-64 w-full rounded-lg border border-line object-contain"
              data-cover-preview
            />
            <div class="flex flex-wrap items-center gap-3">
              <label
                class="inline-flex cursor-pointer items-center gap-2 rounded-lg border border-line px-4 py-2 text-sm font-medium"
                :class="isPending ? 'pointer-events-none opacity-50' : ''"
              >
                <UIcon name="i-lucide-image-plus" />{{ PROJECT_MESSAGES.coverUpload }}
                <input
                  id="project-cover"
                  type="file"
                  accept="image/png,image/jpeg"
                  class="sr-only"
                  :disabled="!canOperate"
                  @change="uploadSelectedCover"
                />
              </label>
              <UButton
                v-if="coverPreview"
                type="button"
                color="neutral"
                variant="outline"
                :disabled="!canOperate"
                @click="removeCover"
                >{{ PROJECT_MESSAGES.coverRemove }}</UButton
              >
              <span v-if="activeOperation === 'upload'" role="status" class="text-sm text-muted">{{
                PROJECT_MESSAGES.coverUploading
              }}</span>
            </div>
          </div>
        </AdminEditorSection>
        <AdminEditorSection
          plain
          title="技术与展示"
          description="标签体现实际使用的技术；首页重点与排序只影响公开展示。"
        >
          <fieldset :disabled="isPending" class="grid gap-6">
            <fieldset>
              <legend class="mb-3 text-sm font-medium">技术标签</legend>
              <USelectMenu
                v-if="projectTags.length"
                id="project-tags"
                v-model="projectForm.tagIds"
                multiple
                value-key="value"
                :items="projectTags.map((tag) => ({ label: tag.name, value: tag.id }))"
                :disabled="isPending"
                class="w-full"
                placeholder="选择技术标签"
                aria-label="技术标签"
                :search-input="{ placeholder: '搜索技术标签…' }"
                ><template #empty>没有匹配的技术标签</template></USelectMenu
              >
              <p v-else class="text-sm text-muted">{{ ADMIN_MESSAGES.tagsEmpty }}</p>
            </fieldset>
            <div class="grid items-start gap-5">
              <UCheckbox
                id="project-featured"
                v-model="projectForm.featured"
                label="首页重点"
                description="纳入首页重点项目展示，首页仍最多展示四张卡片。"
                :disabled="isPending"
              />
              <UFormField label="排序（越小越靠前）" name="sortOrder"
                ><UInput
                  id="project-sort"
                  v-model.number="projectForm.sortOrder"
                  type="number"
                  :min="0"
                  :step="1"
                  :disabled="isPending"
                  class="w-full"
              /></UFormField>
            </div>
          </fieldset>
        </AdminEditorSection>
        <AdminEditorSection
          plain
          title="外部入口（可选）"
          description="仅填写真实 HTTP(S) 链接，关闭展示后访客不会看到该入口。"
        >
          <fieldset :disabled="isPending" class="grid gap-4">
            <p v-if="!projectForm.links.length" class="text-sm text-muted">
              {{ ADMIN_MESSAGES.linksEmpty }}
            </p>
            <div
              v-for="(link, index) in projectForm.links"
              :key="index"
              class="grid gap-4 rounded-xl border border-line p-4 sm:p-5"
            >
              <div class="flex items-center justify-between gap-3">
                <h3 class="text-sm font-semibold">外部入口 {{ index + 1 }}</h3>
                <UButton
                  type="button"
                  color="error"
                  variant="ghost"
                  size="sm"
                  icon="i-lucide-trash-2"
                  :disabled="isPending"
                  :aria-label="'移除外部入口 ' + (index + 1)"
                  @click="removeLink(index)"
                  >移除入口</UButton
                >
              </div>
              <div class="grid gap-4">
                <UFormField label="入口类型" :name="'links.' + index + '.type'"
                  ><USelect
                    v-model="link.type"
                    :items="linkTypes"
                    :disabled="isPending"
                    class="w-full"
                /></UFormField>
                <UFormField label="名称" :name="'links.' + index + '.label'"
                  ><UInput
                    :model-value="link.label ?? ''"
                    :maxlength="ADMIN_PROJECT_LIMITS.linkLabel"
                    :disabled="isPending"
                    class="w-full"
                    @update:model-value="link.label = $event"
                /></UFormField>
              </div>
              <UFormField label="地址" :name="'links.' + index + '.url'"
                ><UInput
                  v-model="link.url"
                  type="url"
                  :maxlength="ADMIN_PROJECT_LIMITS.linkUrl"
                  placeholder="https://"
                  :disabled="isPending"
                  class="w-full"
              /></UFormField>
              <div class="grid items-center gap-4">
                <UFormField label="外链排序" :name="'links.' + index + '.sortOrder'"
                  ><UInput
                    v-model.number="link.sortOrder"
                    type="number"
                    :min="0"
                    :step="1"
                    :disabled="isPending"
                    class="w-full"
                /></UFormField>
                <UCheckbox
                  :model-value="link.visible === true"
                  label="向访客展示"
                  :disabled="isPending"
                  @update:model-value="link.visible = $event === true"
                />
              </div>
            </div>
            <UButton
              type="button"
              color="neutral"
              variant="outline"
              icon="i-lucide-plus"
              class="w-fit"
              :disabled="isPending"
              @click="addLink"
              >添加外部入口</UButton
            >
          </fieldset>
        </AdminEditorSection>
      </div>

      <footer class="project-footer shrink-0 border-t border-default bg-default p-4 sm:px-6">
        <div class="flex flex-wrap items-center justify-between gap-3">
          <div class="flex flex-wrap items-center gap-2">
            <UBadge
              :color="projectStatus === ADMIN_PROJECT_STATUS.PUBLISHED ? 'success' : 'neutral'"
              variant="subtle"
              >{{ projectStatus === ADMIN_PROJECT_STATUS.PUBLISHED ? '已发布' : '草稿' }}</UBadge
            >
            <span class="text-xs text-muted">版本 {{ projectForm.version ?? '未保存' }}</span>
          </div>
          <div class="flex flex-wrap items-center justify-end gap-2">
            <UButton
              type="button"
              color="neutral"
              variant="ghost"
              :disabled="isPending"
              @click="emit('cancel')"
              >取消</UButton
            >
            <UiConfirmAction
              v-if="projectId"
              :label="projectStatus === 'DRAFT' ? '发布' : '下架'"
              :title="projectStatus === 'DRAFT' ? '发布项目？' : '下架项目？'"
              description="仅操作已保存的项目内容。"
              :disabled="!canOperate || isDirty"
              :action="() => transition(projectStatus === 'DRAFT')"
            />
            <UTooltip v-if="projectId" text="重新读取项目">
              <UButton
                type="button"
                color="neutral"
                variant="ghost"
                icon="i-lucide-refresh-cw"
                aria-label="重新读取项目"
                :disabled="isPending"
                :loading="activeOperation === 'load'"
                @click="reloadProjectWithConfirmation"
              />
            </UTooltip>
            <UButton
              type="submit"
              icon="i-lucide-save"
              :disabled="!canOperate || !isDirty"
              :loading="activeOperation === 'save'"
              >保存项目</UButton
            >
          </div>
        </div>
        <p role="status" :class="isDirty ? 'mt-2 text-xs text-muted' : 'sr-only'">
          {{ saveStatusMessage }}
        </p>
      </footer>
    </form>
    <AdminDiscardChanges :open="leaveOpen" @decision="decideLeave" />
  </div>
</template>

<style scoped>
.project-footer :deep(button) {
  min-height: 2.5rem;
  justify-content: center;
}
</style>
