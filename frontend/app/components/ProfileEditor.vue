<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useAdminProfileApi, type AdminProfileVo } from '../api/adminProfile'
import { PROFILE_FIELD_LIMITS, PROFILE_MESSAGES, PROFILE_PUBLIC_DATA_KEY } from '../constants/profile'

const { getProfile, updateProfile } = useAdminProfileApi()
const app = useNuxtApp()
const form = reactive({ displayName: '', headline: '', intro: '', githubUrl: '', email: '', updatedAt: '' })
const loaded = ref(false)
const pending = ref(false)
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
  pending.value = true
  error.value = ''
  notice.value = ''
  try {
    apply(await getProfile())
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : PROFILE_MESSAGES.loadFailed
  } finally {
    pending.value = false
  }
}

async function save() {
  if (!canSave.value) return
  pending.value = true
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
    pending.value = false
  }
}

onMounted(reload)
</script>

<template>
  <main class="mx-auto max-w-4xl px-5 pb-16">
    <AdminNavigation />
    <h1 class="mb-3 text-3xl font-bold">{{ PROFILE_MESSAGES.title }}</h1>
    <p class="mb-6 text-muted">{{ PROFILE_MESSAGES.mediaHint }}</p>
    <p v-if="error" role="alert" class="mb-5 rounded-xl border border-red-500/60 p-4">{{ error }}</p>
    <p v-if="notice" role="status" class="mb-5 text-accent">{{ notice }}</p>
    <p v-if="pending && !loaded" role="status">{{ PROFILE_MESSAGES.loading }}</p>
    <form v-if="loaded" novalidate class="grid gap-5" @submit.prevent="save">
      <fieldset :disabled="pending" class="grid gap-5">
        <label for="profile-name">昵称（必填）<input id="profile-name" v-model="form.displayName" :maxlength="PROFILE_FIELD_LIMITS.displayName" class="field" autocomplete="nickname"></label>
        <label for="profile-headline">职业定位（必填）<input id="profile-headline" v-model="form.headline" :maxlength="PROFILE_FIELD_LIMITS.headline" class="field"></label>
        <label for="profile-intro">自我介绍（必填）<textarea id="profile-intro" v-model="form.intro" :maxlength="PROFILE_FIELD_LIMITS.intro" rows="8" class="field" /></label>
        <label for="profile-github">GitHub（可选）<input id="profile-github" v-model="form.githubUrl" :maxlength="PROFILE_FIELD_LIMITS.githubUrl" type="url" class="field" autocomplete="url"></label>
        <label for="profile-email">Email（可选）<input id="profile-email" v-model="form.email" :maxlength="PROFILE_FIELD_LIMITS.email" type="email" class="field" autocomplete="email"></label>
        <p class="text-sm text-muted">{{ PROFILE_MESSAGES.contactsHint }}</p>
      </fieldset>
      <div class="flex flex-wrap gap-4">
        <button type="submit" :disabled="!canSave" class="action bg-accent/15">{{ pending ? PROFILE_MESSAGES.processing : '保存资料' }}</button>
        <button type="button" :disabled="pending" class="action" @click="reload">重新读取</button>
        <NuxtLink to="/" class="action">查看公开首页</NuxtLink>
      </div>
      <p role="status" class="text-sm text-muted">{{ dirty ? PROFILE_MESSAGES.unsaved : PROFILE_MESSAGES.clean }}</p>
    </form>
    <button v-else-if="!pending" type="button" class="action mt-5" @click="reload">重新读取</button>
  </main>
</template>

<style scoped>
.field { display: block; width: 100%; margin-top: .5rem; padding: .75rem; border: 1px solid var(--site-line); border-radius: .6rem; background: var(--site-surface); color: var(--site-text); }
.action { padding: .65rem 1rem; border: 1px solid var(--site-line); border-radius: .6rem; }
button:disabled { opacity: .5; cursor: not-allowed; }
</style>
