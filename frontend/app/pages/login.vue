<script setup lang="ts">
import { ref } from 'vue'
import { login } from '../api/session'
import { LOGIN_MESSAGES } from '../constants/messages'
import { useAuthState } from '../composables/useAuthState'
import { safeReturnPath } from '../api/permissions'
import { AUTH_ACCESS } from '../constants/auth'
import { REGISTRATION_PATHS } from '../constants/registration'

const config = useRuntimeConfig()
const route = useRoute()
definePageMeta({ alias: ['/admin/login'] })

const username = ref('')
const password = ref('')
const pending = ref(false)
const auth = useAuthState()
const loggedIn = computed(() => auth.state.value.loggedIn)
const errorMessage = ref('')

useSeoMeta({ title: '登录 · 阿霾作品集', robots: 'noindex,nofollow' })

async function submitLogin() {
  if (pending.value) return
  pending.value = true
  errorMessage.value = ''
  try {
    const session = await login(username.value, password.value, config.public.authRsaPublicKey)
    auth.accept(session)
    await auth.refresh()
    // 登录后的业务去向复用统一能力规则，不在页面重新解释角色字符串。
    if (auth.canAccess(AUTH_ACCESS.OWNER)) await navigateTo(safeReturnPath(route.query.returnTo))
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : LOGIN_MESSAGES.loginFailed
  } finally {
    password.value = ''
    pending.value = false
  }
}
// Cookie 由浏览器保管；刷新页面通过通用接口恢复 UI 状态，不读取 HttpOnly Cookie。
onMounted(async () => {
  pending.value = true
  try {
    await auth.refresh()
    if (auth.state.value.username) username.value = auth.state.value.username
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : LOGIN_MESSAGES.sessionFailed
  } finally {
    pending.value = false
  }
})

async function submitLogout() {
  if (pending.value) return
  pending.value = true
  errorMessage.value = ''
  try {
    await auth.signOut()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : LOGIN_MESSAGES.logoutFailed
  } finally {
    pending.value = false
  }
}
</script>

<template>
  <SiteHeader :restore-session="false" />
  <main class="auth-page">
    <section class="auth-intro" aria-labelledby="auth-intro-title">
      <span class="eyebrow">AMAI / PORTFOLIO</span>
      <h2 id="auth-intro-title" class="auth-intro-title">
        探索项目，<br><span class="text-accent">也记录成长。</span>
      </h2>
      <p class="auth-intro-copy text-muted">从 Java 后端到 AI 智能体，<br class="hidden sm:block">把每一次实践，变成下一次进步的起点。</p>
      <div class="auth-directions flex flex-wrap gap-2" aria-label="技术方向">
        <span v-for="label in ['Java Backend', 'AI Agents', 'Engineering']" :key="label" class="rounded-full border border-line px-3 py-1.5 font-mono text-xs text-muted">{{ label }}</span>
      </div>
      <NuxtLink to="/#projects" class="auth-project-link inline-flex items-center gap-2 text-sm font-semibold text-accent underline underline-offset-4">先看看项目 <span aria-hidden="true">↗</span></NuxtLink>
    </section>
    <SurfaceCard as="div" class="auth-card" :max-tilt="2">
      <div class="auth-mark" aria-hidden="true">M</div>
      <form v-if="!loggedIn" class="grid gap-6" :aria-busy="pending" @submit.prevent="submitLogin">
        <div>
          <span class="eyebrow">WELCOME BACK</span>
          <h1 class="mt-3 text-3xl font-bold tracking-tight">登录你的账号</h1>
          <p class="auth-welcome text-sm leading-6 text-muted">{{ LOGIN_MESSAGES.welcome }}</p>
        </div>
        <div class="auth-fields grid">
          <div>
            <label for="username" class="auth-label block text-sm font-semibold">用户名</label>
            <input id="username" v-model.trim="username" name="username" autocomplete="username" placeholder="输入用户名" required :disabled="pending" class="auth-input">
          </div>
          <div>
            <label for="password" class="auth-label block text-sm font-semibold">密码</label>
            <input id="password" v-model="password" name="password" type="password" autocomplete="current-password" placeholder="输入密码" required :disabled="pending" class="auth-input">
          </div>
        </div>
        <p v-if="errorMessage" role="alert" class="auth-error rounded-xl border border-red-500/30 bg-red-500/5 px-4 py-3 text-sm leading-6">{{ errorMessage }}</p>
        <button type="submit" :disabled="pending" class="auth-primary flex w-full items-center justify-center gap-3 rounded-xl px-5 py-3.5 font-semibold">
          {{ pending ? '正在登录…' : '登录' }} <span v-if="!pending" aria-hidden="true">→</span>
        </button>
        <p class="text-center text-sm text-muted">还没有账号？<NuxtLink :to="REGISTRATION_PATHS.page" class="ml-2 font-semibold text-accent underline underline-offset-4">创建访客账号</NuxtLink></p>
      </form>
      <section v-else class="grid gap-6" role="status">
        <div>
          <span class="eyebrow">SIGNED IN</span>
          <h1 class="mt-3 text-3xl font-bold">欢迎回来</h1>
          <p class="mt-3 leading-7 text-muted">{{ auth.state.value.username || username }}，你已登录。</p>
          <p class="mt-1 text-sm text-muted">{{ LOGIN_MESSAGES.accountAccess }}</p>
        </div>
        <p v-if="errorMessage" role="alert" class="auth-error rounded-xl border border-red-500/30 bg-red-500/5 p-4 text-sm">{{ errorMessage }}</p>
        <Auth :access="AUTH_ACCESS.OWNER">
          <NuxtLink to="/admin/projects" class="auth-primary rounded-xl px-5 py-3.5 text-center font-semibold no-underline">管理项目 →</NuxtLink>
        </Auth>
        <NuxtLink to="/#about" class="rounded-xl border border-line px-5 py-3 text-center text-sm font-semibold no-underline">返回作品集</NuxtLink>
        <button type="button" :disabled="pending" class="w-fit text-sm text-muted underline underline-offset-4" @click="submitLogout">{{ pending ? '正在注销…' : '注销账号' }}</button>
      </section>
    </SurfaceCard>
  </main>
</template>

<style src="../assets/css/auth-pages.css"></style>
