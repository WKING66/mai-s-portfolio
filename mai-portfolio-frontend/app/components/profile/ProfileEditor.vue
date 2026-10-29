<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
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
const canSave = computed(() => isLoaded.value && !isPending.value)

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
  if (isDirty.value && !window.confirm(PROFILE_MESSAGES.discardConfirm)) {
    return
  }
  activeOperation.value = 'load'
  try {
    acceptSavedProfile(await getProfile(force))
  } catch (cause) {
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
    feedback.error(cause, PROFILE_MESSAGES.saveFailed)
  } finally {
    activeOperation.value = null
  }
}

onMounted(() => reloadProfile(false))
</script>
<template>
  <div class="grid min-w-0 gap-6" :aria-busy="isPending">
    <p class="text-sm text-muted">{{ PROFILE_MESSAGES.mediaHint }}</p>
    <UCard v-if="!isLoaded">
      <div
        v-if="isPending"
        role="status"
        :aria-label="PROFILE_MESSAGES.loading"
        class="grid min-h-[500px] gap-6"
      >
        <div class="grid gap-5 md:grid-cols-2">
          <USkeleton class="h-16 w-full" /><USkeleton class="h-16 w-full" />
        </div>
        <USkeleton class="h-56 w-full" />
        <div class="grid gap-5 md:grid-cols-2">
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
    <form v-else novalidate class="grid gap-6" @submit.prevent="saveProfile">
      <AdminEditorSection
        title="公开身份"
        description="昵称、职业定位和介绍将用于公开首页与搜索引擎元数据。"
      >
        <fieldset :disabled="isPending" class="grid gap-5">
          <div class="grid gap-5 md:grid-cols-2">
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
              :rows="8"
              :disabled="isPending"
              class="w-full"
          /></UFormField>
        </fieldset>
      </AdminEditorSection>
      <AdminEditorSection title="公开联系方式" :description="PROFILE_MESSAGES.contactsHint">
        <fieldset :disabled="isPending" class="grid gap-5 md:grid-cols-2">
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
      <UCard>
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
      </UCard>
    </form>
  </div>
</template>
