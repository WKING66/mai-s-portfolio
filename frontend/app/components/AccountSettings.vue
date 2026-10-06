<script setup lang="ts">
import { ACCOUNT_MESSAGES, ACCOUNT_RULES } from '../constants/account'
import { REGISTRATION_MESSAGES } from '../constants/registration'
import { useAccountProfile } from '../composables/useAccountProfile'
import { useAccountApi } from '../api/account'
import { useAuthState } from '../composables/useAuthState'

const auth = useAuthState()
const profile = useAccountProfile()
const api = useAccountApi()
const config = useRuntimeConfig()
const nickname = ref('')
const oldPassword = ref('')
const newPassword = ref('')
const confirmation = ref('')
const loading = ref(true)
const saving = ref(false)
const uploading = ref(false)
const changingPassword = ref(false)
const loadError = ref('')
const profileError = ref('')
const passwordError = ref('')
const notice = ref('')
const busy = computed(() => saving.value || uploading.value || changingPassword.value)
const displayName = computed(() => profile.state.value?.nickname || auth.state.value.username || '')
const avatarSrc = computed(() => profile.state.value?.avatarUrl
  ? profile.state.value.avatarUrl + (profile.state.value.avatarUrl.includes('?') ? '&' : '?')
    + 'rev=' + profile.revision.value : undefined)

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    await profile.load()
    nickname.value = profile.state.value?.nickname || ''
  } catch (cause) {
    loadError.value = cause instanceof Error ? cause.message : ACCOUNT_MESSAGES.loadFailed
  } finally { loading.value = false }
}
onMounted(load)

async function saveNickname() {
  if (busy.value) return
  saving.value = true
  profileError.value = ''
  notice.value = ''
  try {
    profile.accept(await api.updateProfile(nickname.value))
    nickname.value = profile.state.value?.nickname || ''
    notice.value = ACCOUNT_MESSAGES.saved
  } catch (cause) {
    profileError.value = cause instanceof Error ? cause.message : ACCOUNT_MESSAGES.saveFailed
  } finally { saving.value = false }
}

async function uploadAvatar(event: Event) {
  const input = event.target
  if (!(input instanceof HTMLInputElement)) return
  const file = input.files?.[0]
  if (!file || busy.value) { input.value = ''; return }
  uploading.value = true
  profileError.value = ''
  notice.value = ''
  try {
    profile.accept(await api.uploadAvatar(file))
    notice.value = ACCOUNT_MESSAGES.avatarSaved
  } catch (cause) {
    profileError.value = cause instanceof Error ? cause.message : ACCOUNT_MESSAGES.avatarFailed
  } finally { uploading.value = false; input.value = '' }
}

async function changePassword() {
  if (busy.value) return
  changingPassword.value = true
  passwordError.value = ''
  try {
    await api.changePassword(oldPassword.value, newPassword.value, confirmation.value, config.public.authRsaPublicKey)
    // 后端提交密码后已撤销全部会话，不能再发送依赖旧 Cookie 的注销请求。
    auth.clear()
    profile.clear()
    await navigateTo('/login?passwordChanged=1')
  } catch (cause) {
    passwordError.value = cause instanceof Error ? cause.message : ACCOUNT_MESSAGES.passwordFailed
  } finally {
    oldPassword.value = ''; newPassword.value = ''; confirmation.value = ''
    changingPassword.value = false
  }
}
</script>

<template>
  <header class="mb-8 flex flex-wrap items-end justify-between gap-4">
    <div><span class="eyebrow">MY ACCOUNT</span><h1 class="mt-2 text-3xl font-bold tracking-tight">个人中心</h1><p class="mt-2 text-sm text-muted">管理你的个人资料与账号安全。</p></div>
    <UButton to="/#about" variant="ghost" color="neutral" icon="i-lucide-arrow-left">返回作品集</UButton>
  </header>
  <p v-if="loading" role="status" class="glass-surface rounded-2xl p-8 text-muted">正在读取个人资料…</p>
  <UAlert v-else-if="loadError" color="error" variant="soft" :title="loadError" :actions="[{ label: '重试', onClick: load }]" />
  <div v-else class="grid items-start gap-6 md:grid-cols-[0.85fr_1.15fr]">
    <section class="glass-surface rounded-3xl p-6 sm:p-8" aria-labelledby="account-profile-title">
      <div class="mb-6 flex items-center gap-4">
        <UAvatar :src="avatarSrc" :alt="displayName" size="3xl" />
        <div class="min-w-0"><h2 id="account-profile-title" class="truncate text-xl font-bold">{{ displayName }}</h2><p class="mt-1 text-sm text-muted">@{{ auth.state.value.username }}</p></div>
      </div>
      <UFormField label="头像" name="avatar" :description="ACCOUNT_MESSAGES.avatarHint" class="mb-6">
        <label class="mt-2 inline-flex cursor-pointer items-center gap-2 rounded-xl border border-line px-4 py-2.5 text-sm font-semibold hover:bg-accent/5 focus-within:outline-2 focus-within:outline-offset-2 focus-within:outline-accent" :class="{ 'pointer-events-none opacity-50': busy }">
          <UIcon name="i-lucide-image-up" class="size-4" /><span>{{ uploading ? '正在上传…' : '选择头像' }}</span>
          <input id="account-avatar" type="file" accept="image/png,image/jpeg" aria-label="选择头像" :disabled="busy" class="sr-only" @change="uploadAvatar">
        </label>
      </UFormField>
      <form class="grid gap-5" @submit.prevent="saveNickname">
        <UFormField label="昵称" name="nickname" :description="ACCOUNT_MESSAGES.nicknameHint">
          <UInput id="account-nickname" v-model="nickname" :maxlength="ACCOUNT_RULES.nicknameMaxLength" :disabled="busy" placeholder="输入你的昵称" size="lg" class="w-full" />
        </UFormField>
        <UAlert v-if="profileError" role="alert" color="error" variant="soft" :title="profileError" />
        <UAlert v-if="notice" role="status" color="success" variant="soft" :title="notice" />
        <UButton type="submit" :loading="saving" :disabled="busy" size="lg" class="justify-center" icon="i-lucide-save">保存资料</UButton>
      </form>
    </section>
    <section class="glass-surface rounded-3xl p-6 sm:p-8" aria-labelledby="account-password-title">
      <div class="mb-6 flex items-center gap-3"><span class="flex size-10 items-center justify-center rounded-xl bg-accent/10 text-accent"><UIcon name="i-lucide-shield-check" class="size-5" /></span><div><h2 id="account-password-title" class="text-xl font-bold">账号安全</h2><p class="mt-1 text-sm text-muted">修改成功后，需要重新登录。</p></div></div>
      <form class="grid gap-5" @submit.prevent="changePassword">
        <UFormField label="当前密码" name="oldPassword"><UInput id="account-old-password" v-model="oldPassword" type="password" autocomplete="current-password" required :disabled="busy" size="lg" class="w-full" /></UFormField>
        <UFormField label="新密码" name="newPassword" :description="REGISTRATION_MESSAGES.passwordHint"><UInput id="account-new-password" v-model="newPassword" type="password" autocomplete="new-password" required :disabled="busy" size="lg" class="w-full" /></UFormField>
        <UFormField label="确认新密码" name="confirmation"><UInput id="account-confirm-password" v-model="confirmation" type="password" autocomplete="new-password" required :disabled="busy" size="lg" class="w-full" /></UFormField>
        <UAlert v-if="passwordError" role="alert" color="error" variant="soft" :title="passwordError" />
        <UButton type="submit" :loading="changingPassword" :disabled="busy" size="lg" class="justify-center" icon="i-lucide-key-round">更新密码</UButton>
      </form>
    </section>
  </div>
</template>
