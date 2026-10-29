<script setup lang="ts">
import { ref } from 'vue'
import { login } from '../api/session'
import { LOGIN_MESSAGES } from '../constants/messages'
import { useAuthState } from '../composables/useAuthState'
import { AUTH_ACCESS } from '../constants/auth'
import { REGISTRATION_PATHS } from '../constants/registration'
import { ACCOUNT_MESSAGES } from '../constants/account'
import { useFeedback } from '../composables/useFeedback'
import { ROUTE_PATH } from '../constants/route'

const runtimeConfig = useRuntimeConfig()
const route = useRoute()
definePageMeta({ alias: ['/admin/login'] })

const username = ref('')
const password = ref('')
const isSubmitting = ref(false)
const authState = useAuthState()
const feedback = useFeedback()
const isLoggedIn = computed(() => authState.state.value.loggedIn)

useSeoMeta({ title: '登录 · 阿霾作品集', robots: 'noindex,nofollow' })

// 1. 登录提交互斥；密码加密与会话请求继续复用已有 API 能力。
async function submitLogin() {
  if (isSubmitting.value) {
    return
  }
  isSubmitting.value = true
  try {
    const loginSession = await login(
      username.value,
      password.value,
      runtimeConfig.public.authRsaPublicKey,
    )
    authState.accept(loginSession)
    if (!authState.canAccess(AUTH_ACCESS.AUTHENTICATED)) {
      throw new Error(LOGIN_MESSAGES.sessionFailed)
    }
    // 2. 接收并核对登录能力后，沿用当前回到首页的去向，不另行解释角色。
    await navigateTo(ROUTE_PATH.root)
    feedback.success(LOGIN_MESSAGES.welcome)
  } catch (cause) {
    feedback.error(cause, LOGIN_MESSAGES.loginFailed)
  } finally {
    password.value = ''
    isSubmitting.value = false
  }
}
// 3. 页面挂载先恢复展示会话；Cookie 由浏览器保管，不读取 HttpOnly Cookie。
onMounted(async () => {
  if (route.query.passwordChanged === '1') {
    feedback.success(ACCOUNT_MESSAGES.passwordChanged)
  }
  isSubmitting.value = true
  try {
    await authState.ensureSession()
    if (authState.state.value.username) {
      username.value = authState.state.value.username
    }
  } catch (cause) {
    feedback.error(cause, LOGIN_MESSAGES.sessionFailed)
  } finally {
    isSubmitting.value = false
  }
})

// 4. 登录页注销也沿用共享会话清理，所有出口都释放提交状态。
async function submitLogout() {
  if (isSubmitting.value) {
    return
  }
  isSubmitting.value = true
  try {
    await authState.signOut()
  } catch (cause) {
    feedback.error(cause, LOGIN_MESSAGES.logoutFailed)
  } finally {
    isSubmitting.value = false
  }
}
</script>

<template>
  <main class="auth-page">
    <section class="auth-intro" aria-labelledby="auth-intro-title">
      <span class="eyebrow">AMAI / PORTFOLIO</span>
      <h2 id="auth-intro-title" class="auth-intro-title">
        探索项目，<br /><span class="text-accent">也记录成长。</span>
      </h2>
      <p class="auth-intro-copy text-muted">
        从 Java 后端到 AI 智能体，<br class="hidden sm:block" />把每一次实践，变成下一次进步的起点。
      </p>
      <div class="auth-directions flex flex-wrap gap-2" aria-label="技术方向">
        <span
          v-for="label in ['Java Backend', 'AI Agents', 'Engineering']"
          :key="label"
          class="rounded-full border border-line px-3 py-1.5 font-mono text-xs text-muted"
          >{{ label }}</span
        >
      </div>
      <NuxtLink
        to="/#projects"
        class="auth-project-link inline-flex items-center gap-2 text-sm font-semibold text-accent underline underline-offset-4"
        >先看看项目 <span aria-hidden="true">↗</span></NuxtLink
      >
    </section>
    <UiSurfaceCard as="div" class="auth-card">
      <div class="auth-mark" aria-hidden="true">M</div>
      <form
        v-if="!isLoggedIn"
        class="grid gap-6"
        :aria-busy="isSubmitting"
        @submit.prevent="submitLogin"
      >
        <div>
          <span class="eyebrow">WELCOME BACK</span>
          <h1 class="mt-3 text-3xl font-bold tracking-tight">登录你的账号</h1>
          <p class="auth-welcome text-sm leading-6 text-muted">{{ LOGIN_MESSAGES.welcome }}</p>
        </div>
        <div class="auth-fields grid">
          <div>
            <label for="username" class="auth-label block text-sm font-semibold">用户名</label>
            <input
              id="username"
              v-model.trim="username"
              name="username"
              autocomplete="username"
              placeholder="输入用户名"
              required
              :disabled="isSubmitting"
              class="auth-input"
            />
          </div>
          <div>
            <label for="password" class="auth-label block text-sm font-semibold">密码</label>
            <input
              id="password"
              v-model="password"
              name="password"
              type="password"
              autocomplete="current-password"
              placeholder="输入密码"
              required
              :disabled="isSubmitting"
              class="auth-input"
            />
          </div>
        </div>
        <button
          type="submit"
          :disabled="isSubmitting"
          class="auth-primary flex w-full items-center justify-center gap-3 rounded-xl px-5 py-3.5 font-semibold"
        >
          {{ isSubmitting ? '正在登录…' : '登录' }}
          <span v-if="!isSubmitting" aria-hidden="true">→</span>
        </button>
        <p class="text-center text-sm text-muted">
          还没有账号？<NuxtLink
            :to="REGISTRATION_PATHS.page"
            class="ml-2 font-semibold text-accent underline underline-offset-4"
            >创建访客账号</NuxtLink
          >
        </p>
      </form>
      <section v-else class="grid gap-6" role="status">
        <div>
          <span class="eyebrow">SIGNED IN</span>
          <h1 class="mt-3 text-3xl font-bold">欢迎回来</h1>
          <p class="mt-3 leading-7 text-muted">
            {{ authState.state.value.username || username }}，你已登录。
          </p>
          <p class="mt-1 text-sm text-muted">{{ LOGIN_MESSAGES.accountAccess }}</p>
        </div>
        <Auth :access="AUTH_ACCESS.OWNER">
          <NuxtLink
            to="/admin/projects"
            class="auth-primary rounded-xl px-5 py-3.5 text-center font-semibold no-underline"
            >管理项目 →</NuxtLink
          >
        </Auth>
        <NuxtLink
          to="/#about"
          class="rounded-xl border border-line px-5 py-3 text-center text-sm font-semibold no-underline"
          >返回作品集</NuxtLink
        >
        <button
          type="button"
          :disabled="isSubmitting"
          class="w-fit text-sm text-muted underline underline-offset-4"
          @click="submitLogout"
        >
          {{ isSubmitting ? '正在注销…' : '注销账号' }}
        </button>
      </section>
    </UiSurfaceCard>
  </main>
</template>

<style src="../assets/css/auth-pages.css"></style>
