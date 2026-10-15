<script setup lang="ts">
import { ACCOUNT_MESSAGES, ACCOUNT_RULES, ACCOUNT_TABS } from '../constants/account'
import { REGISTRATION_MESSAGES } from '../constants/registration'
import { useAccountProfile } from '../composables/useAccountProfile'
import { useAssetApi } from '../api/assets'
import { AUTH_ERROR_CODES, AUTH_MESSAGES } from '../constants/auth'
import { ApiRequestError } from '../api/request'
import { useAccountApi, validateAvatar } from '../api/account'
import { useAuthState } from '../composables/useAuthState'
import { useFeedback } from '../composables/useFeedback'

const authState = useAuthState()
const accountProfile = useAccountProfile()
const accountApi = useAccountApi()
const feedback = useFeedback()
const assetApi = useAssetApi()
const runtimeConfig = useRuntimeConfig()
const nickname = ref(accountProfile.state.value?.nickname || '')
const activeTab = ref('profile')
const oldPassword = ref('')
const newPassword = ref('')
const confirmation = ref('')
const isLoading = ref(!accountProfile.state.value)
const isSavingNickname = ref(false)
const isUploadingAvatar = ref(false)
const isChangingPassword = ref(false)
const hasLoadFailed = ref(false)
const isOperationPending = computed(
  () => isSavingNickname.value || isUploadingAvatar.value || isChangingPassword.value,
)
const accountTabs = computed(() =>
  ACCOUNT_TABS.map((item) => ({ ...item, disabled: isOperationPending.value })),
)
// 1. 切换页签即清空密码输入，隐藏表单不保留敏感值。
watch(activeTab, () => {
  // 切回资料时移除密码输入，不让隐藏表单长期保留敏感值。
  oldPassword.value = ''
  newPassword.value = ''
  confirmation.value = ''
})
const displayName = computed(
  () => accountProfile.state.value?.nickname || authState.state.value.username || '',
)
const avatarSrc = computed(() => accountProfile.state.value?.avatarUrl ?? undefined)

// 2. 资料加载沿用共享账号快照，组件只维护当前表单与重试入口。
async function loadAccountProfile() {
  isLoading.value = !accountProfile.state.value
  hasLoadFailed.value = false
  try {
    await accountProfile.load()
    nickname.value = accountProfile.state.value?.nickname || ''
  } catch {
    // 共享资料加载已弹出当前请求的错误，此处只决定是否显示重试入口。
    hasLoadFailed.value = true
  } finally {
    isLoading.value = false
  }
}
onMounted(loadAccountProfile)

// 3. 各类写操作互斥；昵称提交成功才接收新的当前账号资料。
async function saveNickname() {
  if (isOperationPending.value) {
    return
  }
  isSavingNickname.value = true
  try {
    accountProfile.accept(await accountApi.updateProfile(nickname.value))
    nickname.value = accountProfile.state.value?.nickname || ''
    feedback.success(ACCOUNT_MESSAGES.saved)
  } catch (cause) {
    feedback.error(cause, ACCOUNT_MESSAGES.saveFailed)
  } finally {
    isSavingNickname.value = false
  }
}

// 4. 校验头像后记录账号世代，上传完成仍须核对身份，避免绑定到切换后的账号。
async function uploadAvatar(event: Event) {
  const input = event.target
  if (!(input instanceof HTMLInputElement)) {
    return
  }
  const file = input.files?.[0]
  if (!file || isOperationPending.value) {
    input.value = ''
    return
  }
  isUploadingAvatar.value = true
  try {
    validateAvatar(file)
    const uploadSessionRevision = authState.sessionRevision()
    const uploadedAvatar = await assetApi.upload(file, 'AVATAR')
    if (authState.sessionRevision() !== uploadSessionRevision) {
      throw new ApiRequestError(AUTH_MESSAGES.sessionChanged, null, AUTH_ERROR_CODES.sessionChanged)
    }
    accountProfile.accept(await accountApi.bindAvatar(uploadedAvatar.id))
    feedback.success(ACCOUNT_MESSAGES.avatarSaved)
  } catch (cause) {
    feedback.error(cause, ACCOUNT_MESSAGES.avatarFailed)
  } finally {
    isUploadingAvatar.value = false
    input.value = ''
  }
}

// 5. 密码更新成功后仅清理本地会话，不再用已撤销的 Cookie 发注销请求。
async function changePassword() {
  if (isOperationPending.value) {
    return
  }
  isChangingPassword.value = true
  try {
    await accountApi.changePassword(
      oldPassword.value,
      newPassword.value,
      confirmation.value,
      runtimeConfig.public.authRsaPublicKey,
    )
    // 后端提交密码后已撤销全部会话，不能再发送依赖旧 Cookie 的注销请求。
    authState.clear()
    accountProfile.clear()
    await navigateTo('/login?passwordChanged=1')
  } catch (cause) {
    feedback.error(cause, ACCOUNT_MESSAGES.passwordFailed)
  } finally {
    oldPassword.value = ''
    newPassword.value = ''
    confirmation.value = ''
    isChangingPassword.value = false
  }
}
</script>

<template>
  <header class="mb-8 flex flex-wrap items-end justify-between gap-4">
    <div>
      <span class="eyebrow">MY ACCOUNT</span>
      <h1 class="mt-2 text-3xl font-bold tracking-tight">个人中心</h1>
      <p class="mt-2 text-sm text-muted">管理你的个人资料与账号安全。</p>
    </div>
    <UButton to="/#about" variant="ghost" color="neutral" icon="i-lucide-arrow-left"
      >返回作品集</UButton
    >
  </header>
  <div v-if="isLoading" class="min-h-[570px] max-w-3xl" role="status" aria-label="正在读取个人资料">
    <USkeleton class="mb-6 h-12 w-full" /><USkeleton class="h-[480px] w-full rounded-xl" />
  </div>
  <div v-else-if="hasLoadFailed" class="py-8">
    <UButton
      color="neutral"
      variant="outline"
      icon="i-lucide-refresh-cw"
      @click="loadAccountProfile"
      >重新读取资料</UButton
    >
  </div>
  <UTabs
    v-else
    v-model="activeTab"
    :items="accountTabs"
    variant="link"
    size="lg"
    class="w-full max-w-3xl"
    :ui="{
      list: 'justify-start border-b border-line',
      trigger: 'px-6',
      content: 'min-h-[520px] pt-6',
    }"
  >
    <template #profile>
      <section class="glass-surface rounded-xl p-6 sm:p-8" aria-labelledby="account-profile-title">
        <div class="mb-6 flex items-center gap-4">
          <UAvatar :src="avatarSrc" :alt="displayName" size="3xl" />
          <div class="min-w-0">
            <h2 id="account-profile-title" class="truncate text-xl font-bold">{{ displayName }}</h2>
            <p class="mt-1 text-sm text-muted">@{{ authState.state.value.username }}</p>
          </div>
        </div>
        <UFormField
          label="头像"
          name="avatar"
          :description="ACCOUNT_MESSAGES.avatarHint"
          class="mb-6"
        >
          <label
            class="mt-2 inline-flex cursor-pointer items-center gap-2 rounded-xl border border-line px-4 py-2.5 text-sm font-semibold hover:bg-accent/5 focus-within:outline-2 focus-within:outline-offset-2 focus-within:outline-accent"
            :class="{ 'pointer-events-none opacity-50': isOperationPending }"
          >
            <UIcon name="i-lucide-image-up" class="size-4" /><span>{{
              isUploadingAvatar ? '正在上传…' : '选择头像'
            }}</span>
            <input
              id="account-avatar"
              type="file"
              accept="image/png,image/jpeg"
              aria-label="选择头像"
              :disabled="isOperationPending"
              class="sr-only"
              @change="uploadAvatar"
            />
          </label>
        </UFormField>
        <form class="grid gap-5" @submit.prevent="saveNickname">
          <UFormField label="昵称" name="nickname" :description="ACCOUNT_MESSAGES.nicknameHint">
            <UInput
              id="account-nickname"
              v-model="nickname"
              :maxlength="ACCOUNT_RULES.nicknameMaxLength"
              :disabled="isOperationPending"
              placeholder="输入你的昵称"
              size="lg"
              class="w-full"
            />
          </UFormField>
          <UButton
            type="submit"
            :loading="isSavingNickname"
            :disabled="isOperationPending"
            size="lg"
            class="justify-center"
            icon="i-lucide-save"
            >保存资料</UButton
          >
        </form>
      </section>
    </template>
    <template #password>
      <section class="glass-surface rounded-xl p-6 sm:p-8" aria-labelledby="account-password-title">
        <div class="mb-6 flex items-center gap-3">
          <span class="flex size-10 items-center justify-center rounded-xl bg-accent/10 text-accent"
            ><UIcon name="i-lucide-shield-check" class="size-5"
          /></span>
          <div>
            <h2 id="account-password-title" class="text-xl font-bold">账号安全</h2>
            <p class="mt-1 text-sm text-muted">修改成功后，需要重新登录。</p>
          </div>
        </div>
        <form class="grid gap-5" @submit.prevent="changePassword">
          <UFormField label="当前密码" name="oldPassword"
            ><UInput
              id="account-old-password"
              v-model="oldPassword"
              type="password"
              autocomplete="current-password"
              required
              :disabled="isOperationPending"
              size="lg"
              class="w-full"
          /></UFormField>
          <UFormField
            label="新密码"
            name="newPassword"
            :description="REGISTRATION_MESSAGES.passwordHint"
            ><UInput
              id="account-new-password"
              v-model="newPassword"
              type="password"
              autocomplete="new-password"
              required
              :disabled="isOperationPending"
              size="lg"
              class="w-full"
          /></UFormField>
          <UFormField label="确认新密码" name="confirmation"
            ><UInput
              id="account-confirm-password"
              v-model="confirmation"
              type="password"
              autocomplete="new-password"
              required
              :disabled="isOperationPending"
              size="lg"
              class="w-full"
          /></UFormField>
          <UButton
            type="submit"
            :loading="isChangingPassword"
            :disabled="isOperationPending"
            size="lg"
            class="justify-center"
            icon="i-lucide-key-round"
            >更新密码</UButton
          >
        </form>
      </section>
    </template>
  </UTabs>
</template>
