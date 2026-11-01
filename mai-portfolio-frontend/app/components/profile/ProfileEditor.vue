<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import type { FormError } from '@nuxt/ui'
import { useSiteProfileApi, type SiteProfileResponse } from '../../api/profile'
import { PROFILE_FIELD_LIMITS, PROFILE_MESSAGES } from '../../constants/profile'
import { useFeedback } from '../../composables/useFeedback'

const { getProfile, updateProfile } = useSiteProfileApi()
const feedback = useFeedback()
const profileForm = reactive({
  displayName: '',
  headline: '',
  intro: '',
  githubUrl: '',
  email: '',
  updatedAt: '',
})
const isLoaded = ref(false)
const activeOperation = ref<'load' | 'save' | null>(null)
const isPending = computed(() => activeOperation.value !== null)
const savedProfileJson = ref('')
const isDirty = computed(
  () => isLoaded.value && JSON.stringify(profileForm) !== savedProfileJson.value,
)
const canSave = computed(() => isLoaded.value && !isPending.value && isDirty.value)
const formError = ref('')
const { leaveOpen, requestLeave, decideLeave } = useAdminFormLeave(
  isDirty,
  () => activeOperation.value === 'save',
)
function validateProfile(state: typeof profileForm): FormError[] {
  const errors: FormError[] = []
  for (const [name, label] of [
    ['displayName', '昵称'],
    ['headline', '职业定位'],
    ['intro', '自我介绍'],
  ] as const) {
    if (!state[name].trim()) {
      errors.push({ name, message: `请填写${label}。` })
    }
  }
  if (state.githubUrl.trim()) {
    try {
      const url = new URL(state.githubUrl.trim())
      if (!['http:', 'https:'].includes(url.protocol)) {
        throw new Error()
      }
    } catch {
      errors.push({ name: 'githubUrl', message: '请输入完整的 HTTP(S) 地址。' })
    }
  }
  if (state.email.trim() && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(state.email.trim())) {
    errors.push({ name: 'email', message: '请输入有效的 Email 地址。' })
  }
  return errors
}

// 1. 接收成功返回的资料并记录完整快照，保留服务端并发时间戳原值。
function acceptSavedProfile(profile: SiteProfileResponse) {
  Object.assign(profileForm, {
    displayName: profile.displayName,
    headline: profile.headline,
    intro: profile.intro,
    githubUrl: profile.githubUrl ?? '',
    email: profile.email ?? '',
    updatedAt: profile.updatedAt,
  })
  savedProfileJson.value = JSON.stringify(profileForm)
  isLoaded.value = true
}

// 2. 重读前检查互斥与未保存输入，取消确认时不发请求、不覆盖表单。
async function reloadProfile(force = true) {
  if (isPending.value) {
    return
  }
  if (!(await requestLeave())) {
    return
  }
  formError.value = ''
  activeOperation.value = 'load'
  try {
    acceptSavedProfile(await getProfile(force))
  } catch (cause) {
    formError.value = cause instanceof Error ? cause.message : PROFILE_MESSAGES.loadFailed
    feedback.error(cause, PROFILE_MESSAGES.loadFailed)
  } finally {
    activeOperation.value = null
  }
}

// 3. 可选联系方式只在提交时转为空值，失败保持用户输入并释放操作状态。
async function saveProfile() {
  if (!canSave.value) {
    return
  }
  formError.value = ''
  activeOperation.value = 'save'
  try {
    acceptSavedProfile(
      await updateProfile({
        ...profileForm,
        githubUrl: profileForm.githubUrl.trim() || null,
        email: profileForm.email.trim() || null,
      }),
    )
    feedback.success(PROFILE_MESSAGES.saved)
  } catch (cause) {
    // 409/422/CSRF 错误保留表单，权限错误则由父 Auth 和统一拦截器清理。
    formError.value = cause instanceof Error ? cause.message : PROFILE_MESSAGES.saveFailed
    feedback.error(cause, PROFILE_MESSAGES.saveFailed)
  } finally {
    activeOperation.value = null
  }
}

onMounted(() => reloadProfile(false))
</script>
<template>
  <div class="mx-auto grid w-full max-w-[850px] min-w-0 gap-6" :aria-busy="isPending">
    <UAlert v-if="formError" color="error" title="资料操作未完成" :description="formError" />

    <UCard v-if="!isLoaded">
      <div
        v-if="isPending"
        role="status"
        :aria-label="PROFILE_MESSAGES.loading"
        class="grid min-h-[500px] gap-6"
      >
        <div class="grid gap-5">
          <USkeleton class="h-16 w-full" /><USkeleton class="h-16 w-full" />
        </div>
        <USkeleton class="h-56 w-full" />
        <div class="grid gap-5">
          <USkeleton class="h-16 w-full" /><USkeleton class="h-16 w-full" />
        </div>
      </div>
      <div v-else class="flex justify-center py-8">
        <UButton
          type="button"
          color="neutral"
          variant="outline"
          icon="i-lucide-refresh-cw"
          @click="reloadProfile()"
          >重新读取</UButton
        >
      </div>
    </UCard>
    <UCard v-else
      ><UForm
        :state="profileForm"
        :validate="validateProfile"
        class="grid gap-7"
        @submit="saveProfile"
      >
        <AdminEditorSection
          plain
          title="公开身份"
          description="昵称、职业定位和介绍将用于公开首页与搜索引擎元数据。"
        >
          <fieldset :disabled="isPending" class="grid gap-5">
            <div class="grid gap-5">
              <UFormField label="昵称" name="displayName" required
                ><UInput
                  id="profile-name"
                  v-model="profileForm.displayName"
                  :maxlength="PROFILE_FIELD_LIMITS.displayName"
                  :disabled="isPending"
                  autocomplete="nickname"
                  class="w-full"
              /></UFormField>
              <UFormField label="职业定位" name="headline" required
                ><UInput
                  id="profile-headline"
                  v-model="profileForm.headline"
                  :maxlength="PROFILE_FIELD_LIMITS.headline"
                  :disabled="isPending"
                  class="w-full"
              /></UFormField>
            </div>
            <UFormField label="自我介绍" name="intro" required
              ><UTextarea
                id="profile-intro"
                v-model="profileForm.intro"
                :maxlength="PROFILE_FIELD_LIMITS.intro"
                :rows="5"
                :disabled="isPending"
                class="w-full"
            /></UFormField>
          </fieldset>
        </AdminEditorSection>
        <AdminEditorSection plain title="公开联系方式" :description="PROFILE_MESSAGES.contactsHint">
          <fieldset :disabled="isPending" class="grid gap-5">
            <UFormField label="GitHub（可选）" name="githubUrl"
              ><UInput
                id="profile-github"
                v-model="profileForm.githubUrl"
                :maxlength="PROFILE_FIELD_LIMITS.githubUrl"
                type="url"
                autocomplete="url"
                :disabled="isPending"
                placeholder="https://github.com/…"
                class="w-full"
            /></UFormField>
            <UFormField label="Email（可选）" name="email"
              ><UInput
                id="profile-email"
                v-model="profileForm.email"
                :maxlength="PROFILE_FIELD_LIMITS.email"
                type="email"
                autocomplete="email"
                :disabled="isPending"
                class="w-full"
            /></UFormField>
          </fieldset>
        </AdminEditorSection>
        <div class="border-t border-default pt-5">
          <div class="flex flex-wrap items-center justify-between gap-4">
            <p role="status" class="text-sm text-muted">
              {{ isDirty ? PROFILE_MESSAGES.unsaved : PROFILE_MESSAGES.clean }}
            </p>
            <div class="flex flex-wrap gap-3">
              <UButton
                type="submit"
                icon="i-lucide-save"
                :disabled="!canSave"
                :loading="activeOperation === 'save'"
                >保存资料</UButton
              >
              <UButton
                type="button"
                color="neutral"
                variant="outline"
                icon="i-lucide-refresh-cw"
                :disabled="isPending"
                :loading="activeOperation === 'load'"
                @click="reloadProfile()"
                >重新读取</UButton
              >
            </div>
          </div>
        </div>
      </UForm></UCard
    >
    <AdminDiscardChanges :open="leaveOpen" @decision="decideLeave" />
  </div>
</template>
