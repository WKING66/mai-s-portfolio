<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useAdminProfileApi, type AdminProfileVo } from '../api/adminProfile'
import { PROFILE_FIELD_LIMITS, PROFILE_MESSAGES, PROFILE_PUBLIC_DATA_KEY } from '../constants/profile'

const { getProfile, updateProfile } = useAdminProfileApi()
const app = useNuxtApp()
const form = reactive({ displayName: '', headline: '', intro: '', githubUrl: '', email: '', updatedAt: '' })
const loaded = ref(false)
const operation = ref<'load' | 'save' | null>(null)
const pending = computed(() => operation.value !== null)
const error = ref('')
const notice = ref('')
const savedSnapshot = ref('')
const dirty = computed(() => loaded.value && JSON.stringify(form) !== savedSnapshot.value)
const canSave = computed(() => loaded.value && !pending.value)

function apply(profile: AdminProfileVo) {
  Object.assign(form, {
    displayName: profile.displayName, headline: profile.headline, intro: profile.intro,
    githubUrl: profile.githubUrl ?? '', email: profile.email ?? '', updatedAt: profile.updatedAt,
  })
  savedSnapshot.value = JSON.stringify(form)
  loaded.value = true
}

async function reload() {
  if (pending.value) return
  if (dirty.value && !window.confirm(PROFILE_MESSAGES.discardConfirm)) return
  operation.value = 'load'
  error.value = ''
  notice.value = ''
  try {
    apply(await getProfile())
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : PROFILE_MESSAGES.loadFailed
  } finally {
    operation.value = null
  }
}

async function save() {
  if (!canSave.value) return
  operation.value = 'save'
  error.value = ''
  notice.value = ''
  try {
    apply(await updateProfile({
      ...form, githubUrl: form.githubUrl.trim() || null, email: form.email.trim() || null,
    }))
    // 同一客户端刚访问过首页时也须重新读取；不把管理资料塞进公开缓存。
    app.runWithContext(() => clearNuxtData(PROFILE_PUBLIC_DATA_KEY))
    notice.value = PROFILE_MESSAGES.saved
  } catch (cause) {
    // 409/422/CSRF 错误保留表单，权限错误则由父 Auth 和统一拦截器清理。
    error.value = cause instanceof Error ? cause.message : PROFILE_MESSAGES.saveFailed
  } finally {
    operation.value = null
  }
}

onMounted(reload)
</script>
<template>
  <div class="grid gap-6" :aria-busy="pending">
    <UAlert role="status" color="neutral" variant="subtle" :title="PROFILE_MESSAGES.mediaHint" icon="i-lucide-info" />
    <UAlert v-if="error" role="alert" color="error" variant="subtle" :title="error" icon="i-lucide-circle-alert" />
    <UAlert v-if="notice" role="status" color="success" variant="subtle" :title="notice" icon="i-lucide-circle-check" />
    <UCard v-if="!loaded">
      <p v-if="pending" role="status" class="py-8 text-center text-muted">{{ PROFILE_MESSAGES.loading }}</p>
      <div v-else class="flex justify-center py-8"><UButton type="button" color="neutral" variant="outline" icon="i-lucide-refresh-cw" @click="reload">重新读取</UButton></div>
    </UCard>
    <form v-else novalidate class="grid gap-6" @submit.prevent="save">
      <AdminEditorSection title="公开身份" description="昵称、职业定位和介绍将用于公开首页与搜索引擎元数据。">
        <fieldset :disabled="pending" class="grid gap-5">
          <div class="grid gap-5 md:grid-cols-2">
            <UFormField label="昵称" name="displayName" required><UInput id="profile-name" v-model="form.displayName" :maxlength="PROFILE_FIELD_LIMITS.displayName" :disabled="pending" autocomplete="nickname" class="w-full" /></UFormField>
            <UFormField label="职业定位" name="headline" required><UInput id="profile-headline" v-model="form.headline" :maxlength="PROFILE_FIELD_LIMITS.headline" :disabled="pending" class="w-full" /></UFormField>
          </div>
          <UFormField label="自我介绍" name="intro" required><UTextarea id="profile-intro" v-model="form.intro" :maxlength="PROFILE_FIELD_LIMITS.intro" :rows="8" :disabled="pending" class="w-full" /></UFormField>
        </fieldset>
      </AdminEditorSection>
      <AdminEditorSection title="公开联系方式" :description="PROFILE_MESSAGES.contactsHint">
        <fieldset :disabled="pending" class="grid gap-5 md:grid-cols-2">
          <UFormField label="GitHub（可选）" name="githubUrl"><UInput id="profile-github" v-model="form.githubUrl" :maxlength="PROFILE_FIELD_LIMITS.githubUrl" type="url" autocomplete="url" :disabled="pending" placeholder="https://github.com/…" class="w-full" /></UFormField>
          <UFormField label="Email（可选）" name="email"><UInput id="profile-email" v-model="form.email" :maxlength="PROFILE_FIELD_LIMITS.email" type="email" autocomplete="email" :disabled="pending" class="w-full" /></UFormField>
        </fieldset>
      </AdminEditorSection>
      <UCard>
        <div class="flex flex-wrap items-center justify-between gap-4">
          <p role="status" class="text-sm text-muted">{{ dirty ? PROFILE_MESSAGES.unsaved : PROFILE_MESSAGES.clean }}</p>
          <div class="flex flex-wrap gap-3">
            <UButton type="submit" icon="i-lucide-save" :disabled="!canSave" :loading="operation === 'save'">保存资料</UButton>
            <UButton type="button" color="neutral" variant="outline" icon="i-lucide-refresh-cw" :disabled="pending" :loading="operation === 'load'" @click="reload">重新读取</UButton>
          </div>
        </div>
      </UCard>
    </form>
  </div>
</template>
