<script setup lang="ts">
import { ref } from 'vue'
import { login } from '../api/session'
import { LOGIN_MESSAGES } from '../constants/messages'
import { useAuthState } from '../composables/useAuthState'
import { safeReturnPath } from '../api/permissions'
import { AUTH_ACCESS } from '../constants/auth'

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
  <main class="login-page mx-auto grid w-full max-w-[1200px] items-center gap-12 px-5 py-12 lg:grid-cols-[1.1fr_1fr] lg:gap-20 lg:py-20">
    <section class="login-intro" aria-labelledby="login-intro-title">
      <span class="eyebrow">AMAI / PORTFOLIO</span>
      <h2 id="login-intro-title" class="mt-5 text-[clamp(2.6rem,5vw,4.6rem)] leading-[1.15] font-bold tracking-[-.055em]">
        探索项目，<br><span class="text-accent">也记录成长。</span>
      </h2>
      <p class="mt-6 max-w-md leading-8 text-muted">从 Java 后端到 AI 智能体，<br class="hidden sm:block">把每一次实践，变成下一次进步的起点。</p>
      <div class="mt-8 flex flex-wrap gap-3" aria-label="技术方向">
        <span v-for="label in ['Java Backend', 'AI Agents', 'Engineering']" :key="label" class="rounded-full border border-line px-4 py-2 font-mono text-xs text-muted">{{ label }}</span>
      </div>
      <NuxtLink to="/#projects" class="mt-9 inline-flex items-center gap-2 text-sm font-semibold text-accent underline underline-offset-4">先看看项目 <span aria-hidden="true">↗</span></NuxtLink>
    </section>
    <SurfaceCard as="div" class="login-card w-full px-6 py-8 sm:p-10" :max-tilt="2">
      <div class="login-mark mb-7 grid size-12 place-items-center rounded-2xl text-lg font-black" aria-hidden="true">M</div>
      <form v-if="!loggedIn" class="grid gap-6" :aria-busy="pending" @submit.prevent="submitLogin">
        <div>
          <span class="eyebrow">WELCOME BACK</span>
          <h1 class="mt-3 text-3xl font-bold tracking-tight">登录你的账号</h1>
          <p class="mt-3 text-sm leading-6 text-muted">{{ LOGIN_MESSAGES.welcome }}</p>
        </div>
        <div class="grid gap-5">
          <div>
            <label for="username" class="mb-2 block text-sm font-semibold">用户名</label>
            <input id="username" v-model.trim="username" name="username" autocomplete="username" placeholder="输入用户名" required :disabled="pending" class="login-input">
          </div>
          <div>
            <label for="password" class="mb-2 block text-sm font-semibold">密码</label>
            <input id="password" v-model="password" name="password" type="password" autocomplete="current-password" placeholder="输入密码" required :disabled="pending" class="login-input">
          </div>
        </div>
        <p v-if="errorMessage" role="alert" class="login-error rounded-xl border border-red-500/30 bg-red-500/5 px-4 py-3 text-sm leading-6">{{ errorMessage }}</p>
        <button type="submit" :disabled="pending" class="login-primary flex w-full items-center justify-center gap-3 rounded-xl px-5 py-3.5 font-semibold">
          {{ pending ? '正在登录…' : '登录' }} <span v-if="!pending" aria-hidden="true">→</span>
        </button>
        <p class="border-t border-line pt-5 text-xs leading-6 text-muted">{{ LOGIN_MESSAGES.encryptedSubmission }}<br>{{ LOGIN_MESSAGES.accountAccess }}</p>
      </form>
      <section v-else class="grid gap-6" role="status">
        <div>
          <span class="eyebrow">SIGNED IN</span>
          <h1 class="mt-3 text-3xl font-bold">欢迎回来</h1>
          <p class="mt-3 leading-7 text-muted">{{ auth.state.value.username || username }}，你已登录。</p>
          <p class="mt-1 text-sm text-muted">{{ LOGIN_MESSAGES.accountAccess }}</p>
        </div>
        <p v-if="errorMessage" role="alert" class="login-error rounded-xl border border-red-500/30 bg-red-500/5 p-4 text-sm">{{ errorMessage }}</p>
        <Auth :access="AUTH_ACCESS.OWNER">
          <NuxtLink to="/admin/projects" class="login-primary rounded-xl px-5 py-3.5 text-center font-semibold no-underline">管理项目 →</NuxtLink>
        </Auth>
        <NuxtLink to="/#about" class="rounded-xl border border-line px-5 py-3 text-center text-sm font-semibold no-underline">返回作品集</NuxtLink>
        <button type="button" :disabled="pending" class="w-fit text-sm text-muted underline underline-offset-4" @click="submitLogout">{{ pending ? '正在注销…' : '注销账号' }}</button>
      </section>
    </SurfaceCard>
  </main>
</template>

<style scoped>
.login-page { min-height: calc(100svh - 70px); }
.login-card { max-width: 480px; justify-self: end; }
.login-mark { color: #071018; background: linear-gradient(120deg, var(--site-accent), var(--site-accent-secondary)); }
.login-primary { color: var(--site-bg); background: var(--site-accent); cursor: pointer; transition: filter .2s ease; }
.login-primary:hover { filter: brightness(1.08); }
.login-input { display: block; width: 100%; border: 1px solid var(--site-line); border-radius: 12px; background: color-mix(in srgb, var(--site-bg) 65%, transparent); padding: 13px 15px; color: var(--site-text); font: inherit; }
.login-input::placeholder { color: var(--site-muted); }
.login-input:focus-visible { outline: 2px solid var(--site-accent); outline-offset: 3px; }
.login-error { color: light-dark(#a92136, #ffb7c2); }
button:disabled, input:disabled { opacity: .6; cursor: wait; }
@media (max-width: 1023px) { .login-intro, .login-card { justify-self: center; width: 100%; max-width: 480px; } }
@media (prefers-reduced-motion: reduce) { .login-primary { transition: none; } }
</style>
