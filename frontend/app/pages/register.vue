<script setup lang="ts">
import { computed, ref } from 'vue'
import { register } from '../api/registration'
import { useAuthState } from '../composables/useAuthState'
import { AUTH_ACCESS, AUTH_PATHS } from '../constants/auth'
import { LOGIN_MESSAGES } from '../constants/messages'
import { REGISTRATION_MESSAGES, REGISTRATION_RULES } from '../constants/registration'

const config = useRuntimeConfig()
const auth = useAuthState()
const username = ref('')
const password = ref('')
const pending = ref(true)
const sessionVerified = ref(false)
const errorMessage = ref('')
const registeredUsername = ref('')
const loggedIn = computed(() => auth.state.value.loggedIn)

useSeoMeta({ title: '注册 · 阿霾作品集', robots: 'noindex,nofollow' })

async function restoreSession() {
  pending.value = true
  errorMessage.value = ''
  try {
    await auth.refresh()
    sessionVerified.value = true
  } catch (error) {
    sessionVerified.value = false
    errorMessage.value = error instanceof Error ? error.message : LOGIN_MESSAGES.sessionFailed
  } finally {
    pending.value = false
  }
}

onMounted(restoreSession)

async function submitRegistration() {
  // 会话确认后才允许注册；已登录用户不能在此页面静默切换账号。
  if (pending.value || !sessionVerified.value || loggedIn.value) return
  pending.value = true
  errorMessage.value = ''
  try {
    const registration = await register(username.value, password.value, config.public.authRsaPublicKey)
    registeredUsername.value = registration.username
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : REGISTRATION_MESSAGES.registerFailed
  } finally {
    // 密码只存在当前输入状态；成功或失败后清空，不写入路由或持久化存储。
    password.value = ''
    pending.value = false
  }
}

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
  <main class="register-page mx-auto grid w-full max-w-[1200px] items-center gap-12 px-5 py-12 lg:grid-cols-[1.1fr_1fr] lg:gap-20 lg:py-20">
    <section class="register-intro" aria-labelledby="register-intro-title">
      <span class="eyebrow">AMAI / PORTFOLIO</span>
      <h2 id="register-intro-title" class="mt-5 text-[clamp(2.6rem,5vw,4.6rem)] leading-[1.15] font-bold tracking-[-.055em]">
        从好奇开始，<br><span class="text-accent">一起探索更多。</span>
      </h2>
      <p class="mt-6 max-w-md leading-8 text-muted">从 Java 后端到 AI 智能体，<br class="hidden sm:block">在项目与实践里，找到值得交流的灵感。</p>
      <div class="mt-8 flex flex-wrap gap-3" aria-label="技术方向">
        <span v-for="label in ['Java Backend', 'AI Agents', 'Engineering']" :key="label" class="rounded-full border border-line px-4 py-2 font-mono text-xs text-muted">{{ label }}</span>
      </div>
      <NuxtLink to="/#projects" class="mt-9 inline-flex items-center gap-2 text-sm font-semibold text-accent underline underline-offset-4">先看看项目 <span aria-hidden="true">↗</span></NuxtLink>
    </section>
    <SurfaceCard as="div" class="register-card w-full px-6 py-8 sm:p-10" :max-tilt="2">
      <div class="register-mark mb-7 grid size-12 place-items-center rounded-2xl text-lg font-black" aria-hidden="true">M</div>
      <section v-if="!sessionVerified" class="grid gap-6" :aria-busy="pending">
        <h1 class="text-3xl font-bold tracking-tight">创建访客账号</h1>
        <p v-if="pending" role="status" class="text-sm text-muted">正在确认账号状态…</p>
        <p v-if="errorMessage" role="alert" class="register-error rounded-xl border border-red-500/30 bg-red-500/5 px-4 py-3 text-sm leading-6">{{ errorMessage }}</p>
        <button v-if="!pending" type="button" class="register-primary rounded-xl px-5 py-3.5 font-semibold" @click="restoreSession">重新确认账号状态</button>
      </section>
      <section v-else-if="loggedIn" class="grid gap-6" role="status">
        <div>
          <span class="eyebrow">SIGNED IN</span>
          <h1 class="mt-3 text-3xl font-bold tracking-tight">当前账号已登录</h1>
          <p class="mt-3 leading-7 text-muted">{{ auth.state.value.username }}，{{ REGISTRATION_MESSAGES.signedIn }}</p>
        </div>
        <p v-if="errorMessage" role="alert" class="register-error rounded-xl border border-red-500/30 bg-red-500/5 p-4 text-sm">{{ errorMessage }}</p>
        <Auth :access="AUTH_ACCESS.OWNER">
          <NuxtLink :to="AUTH_PATHS.manageProjects" class="register-primary rounded-xl px-5 py-3.5 text-center font-semibold no-underline">管理项目 →</NuxtLink>
        </Auth>
        <NuxtLink to="/#about" class="rounded-xl border border-line px-5 py-3 text-center text-sm font-semibold no-underline">返回作品集</NuxtLink>
        <button type="button" :disabled="pending" class="w-fit text-sm text-muted underline underline-offset-4" @click="submitLogout">{{ pending ? '正在注销…' : '注销账号后注册' }}</button>
      </section>
      <section v-else-if="registeredUsername" class="grid gap-6" role="status">
        <div>
          <span class="eyebrow">WELCOME ABOARD</span>
          <h1 class="mt-3 text-3xl font-bold tracking-tight">注册成功</h1>
          <p class="mt-3 leading-7 text-muted">{{ registeredUsername }}，{{ REGISTRATION_MESSAGES.success }}</p>
          <p class="mt-3 text-sm leading-6 text-muted">{{ REGISTRATION_MESSAGES.accountAccess }}</p>
        </div>
        <NuxtLink :to="AUTH_PATHS.login" class="register-primary rounded-xl px-5 py-3.5 text-center font-semibold no-underline">前往登录 →</NuxtLink>
        <NuxtLink to="/#projects" class="rounded-xl border border-line px-5 py-3 text-center text-sm font-semibold no-underline">返回作品集</NuxtLink>
      </section>
      <form v-else class="grid gap-6" :aria-busy="pending" @submit.prevent="submitRegistration">
        <div>
          <span class="eyebrow">WELCOME ABOARD</span>
          <h1 class="mt-3 text-3xl font-bold tracking-tight">创建访客账号</h1>
          <p class="mt-3 text-sm leading-6 text-muted">{{ REGISTRATION_MESSAGES.welcome }}</p>
        </div>
        <div class="grid gap-5">
          <div>
            <label for="register-username" class="mb-2 block text-sm font-semibold">用户名</label>
            <input id="register-username" v-model.trim="username" name="username" autocomplete="username" placeholder="输入用户名" required :minlength="REGISTRATION_RULES.usernameMinLength" :maxlength="REGISTRATION_RULES.usernameMaxLength" pattern="[A-Za-z0-9_\-]+" aria-describedby="register-username-hint" :disabled="pending" class="register-input">
            <p id="register-username-hint" class="mt-2 text-xs leading-5 text-muted">{{ REGISTRATION_MESSAGES.usernameHint }}</p>
          </div>
          <div>
            <label for="register-password" class="mb-2 block text-sm font-semibold">密码</label>
            <input id="register-password" v-model="password" name="password" type="password" autocomplete="new-password" placeholder="设置密码" required aria-describedby="register-password-hint" :disabled="pending" class="register-input">
            <p id="register-password-hint" class="mt-2 text-xs leading-5 text-muted">{{ REGISTRATION_MESSAGES.passwordHint }}</p>
          </div>
        </div>
        <p v-if="errorMessage" role="alert" class="register-error rounded-xl border border-red-500/30 bg-red-500/5 px-4 py-3 text-sm leading-6">{{ errorMessage }}</p>
        <button type="submit" :disabled="pending" class="register-primary flex w-full items-center justify-center gap-3 rounded-xl px-5 py-3.5 font-semibold">
          {{ pending ? '正在注册…' : '创建账号' }} <span v-if="!pending" aria-hidden="true">→</span>
        </button>
        <p class="text-center text-sm text-muted">已有账号？<NuxtLink :to="AUTH_PATHS.login" class="ml-2 font-semibold text-accent underline underline-offset-4">前往登录</NuxtLink></p>
        <p class="border-t border-line pt-5 text-xs leading-6 text-muted">{{ LOGIN_MESSAGES.encryptedSubmission }}<br>{{ REGISTRATION_MESSAGES.accountAccess }}</p>
      </form>
    </SurfaceCard>
  </main>
</template>

<style scoped>
.register-page { min-height: calc(100svh - 70px); }
.register-card { max-width: 480px; justify-self: end; }
.register-mark { color: #071018; background: linear-gradient(120deg, var(--site-accent), var(--site-accent-secondary)); }
.register-primary { color: var(--site-bg); background: var(--site-accent); cursor: pointer; transition: filter .2s ease; }
.register-primary:hover { filter: brightness(1.08); }
.register-input { display: block; width: 100%; border: 1px solid var(--site-line); border-radius: 12px; background: color-mix(in srgb, var(--site-bg) 65%, transparent); padding: 13px 15px; color: var(--site-text); font: inherit; }
.register-input::placeholder { color: var(--site-muted); }
.register-input:focus-visible { outline: 2px solid var(--site-accent); outline-offset: 3px; }
.register-error { color: light-dark(#a92136, #ffb7c2); }
button:disabled, input:disabled { opacity: .6; cursor: wait; }
@media (max-width: 1023px) { .register-intro, .register-card { justify-self: center; width: 100%; max-width: 480px; } }
@media (prefers-reduced-motion: reduce) { .register-primary { transition: none; } }
</style>
