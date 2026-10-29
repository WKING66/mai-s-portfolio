<script setup lang="ts">
import { computed, ref } from 'vue'
import { register } from '../api/registration'
import { useAuthState } from '../composables/useAuthState'
import { AUTH_ACCESS, AUTH_PATHS } from '../constants/auth'
import { LOGIN_MESSAGES } from '../constants/messages'
import { REGISTRATION_MESSAGES, REGISTRATION_RULES } from '../constants/registration'
import { useFeedback } from '../composables/useFeedback'

const runtimeConfig = useRuntimeConfig()
const authState = useAuthState()
const feedback = useFeedback()
const username = ref('')
const password = ref('')
const isSubmitting = ref(true)
const isSessionVerified = ref(false)
const registeredUsername = ref('')
const isLoggedIn = computed(() => authState.state.value.loggedIn)

useSeoMeta({ title: '注册 · 阿霾作品集', robots: 'noindex,nofollow' })

// 1. 页面就绪前先确认会话；注册不隐式替换当前已登录账号。
async function restoreSession(force = false) {
  isSubmitting.value = true
  try {
    await (force ? authState.refresh() : authState.ensureSession())
    isSessionVerified.value = true
  } catch (cause) {
    isSessionVerified.value = false
    feedback.error(cause, LOGIN_MESSAGES.sessionFailed)
  } finally {
    isSubmitting.value = false
  }
}

onMounted(() => restoreSession())

// 2. 已确认的访客状态才允许提交，保留注册结果，成功或失败均清空密码。
async function submitRegistration() {
  // 会话确认后才允许注册；已登录用户不能在此页面静默切换账号。
  if (isSubmitting.value || !isSessionVerified.value || isLoggedIn.value) {
    return
  }
  isSubmitting.value = true
  try {
    const registeredAccount = await register(
      username.value,
      password.value,
      runtimeConfig.public.authRsaPublicKey,
    )
    registeredUsername.value = registeredAccount.username
    feedback.success(registeredAccount.username + '，' + REGISTRATION_MESSAGES.success)
  } catch (cause) {
    feedback.error(cause, REGISTRATION_MESSAGES.registerFailed)
  } finally {
    // 密码只存在当前输入状态；成功或失败后清空，不写入路由或持久化存储。
    password.value = ''
    isSubmitting.value = false
  }
}

// 3. 已登录账号须明确注销后再注册，不在此表单复用旧身份。
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
        从好奇开始，<br /><span class="text-accent">一起探索更多。</span>
      </h2>
      <p class="auth-intro-copy text-muted">
        从 Java 后端到 AI 智能体，<br class="hidden sm:block" />在项目与实践里，找到值得交流的灵感。
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
      <section v-if="!isSessionVerified" class="grid gap-6" :aria-busy="isSubmitting">
        <h1 class="text-3xl font-bold tracking-tight">创建访客账号</h1>
        <p v-if="isSubmitting" role="status" class="text-sm text-muted">正在确认账号状态…</p>
        <button
          v-if="!isSubmitting"
          type="button"
          class="auth-primary rounded-xl px-5 py-3.5 font-semibold"
          @click="restoreSession(true)"
        >
          重新确认账号状态
        </button>
      </section>
      <section v-else-if="isLoggedIn" class="grid gap-6" role="status">
        <div>
          <span class="eyebrow">SIGNED IN</span>
          <h1 class="mt-3 text-3xl font-bold tracking-tight">当前账号已登录</h1>
          <p class="mt-3 leading-7 text-muted">
            {{ authState.state.value.username }}，{{ REGISTRATION_MESSAGES.signedIn }}
          </p>
        </div>
        <Auth :access="AUTH_ACCESS.OWNER">
          <NuxtLink
            :to="AUTH_PATHS.manageProjects"
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
          {{ isSubmitting ? '正在注销…' : '注销账号后注册' }}
        </button>
      </section>
      <section v-else-if="registeredUsername" class="grid gap-6" role="status">
        <div>
          <span class="eyebrow">WELCOME ABOARD</span>
          <h1 class="mt-3 text-3xl font-bold tracking-tight">注册成功</h1>
          <p class="auth-welcome text-sm leading-6 text-muted">
            {{ REGISTRATION_MESSAGES.accountAccess }}
          </p>
        </div>
        <NuxtLink
          :to="AUTH_PATHS.login"
          class="auth-primary rounded-xl px-5 py-3.5 text-center font-semibold no-underline"
          >前往登录 →</NuxtLink
        >
        <NuxtLink
          to="/#projects"
          class="rounded-xl border border-line px-5 py-3 text-center text-sm font-semibold no-underline"
          >返回作品集</NuxtLink
        >
      </section>
      <form
        v-else
        class="grid gap-6"
        :aria-busy="isSubmitting"
        @submit.prevent="submitRegistration"
      >
        <div>
          <span class="eyebrow">WELCOME ABOARD</span>
          <h1 class="mt-3 text-3xl font-bold tracking-tight">创建访客账号</h1>
          <p class="auth-welcome text-sm leading-6 text-muted">
            {{ REGISTRATION_MESSAGES.welcome }}
          </p>
        </div>
        <div class="auth-fields grid">
          <div>
            <label for="register-username" class="auth-label block text-sm font-semibold"
              >用户名</label
            >
            <input
              id="register-username"
              v-model.trim="username"
              name="username"
              autocomplete="username"
              placeholder="输入用户名"
              required
              :minlength="REGISTRATION_RULES.usernameMinLength"
              :maxlength="REGISTRATION_RULES.usernameMaxLength"
              pattern="[A-Za-z0-9_\-]+"
              aria-describedby="register-username-hint"
              :disabled="isSubmitting"
              class="auth-input"
            />
            <p id="register-username-hint" class="mt-2 text-xs leading-5 text-muted">
              {{ REGISTRATION_MESSAGES.usernameHint }}
            </p>
          </div>
          <div>
            <label for="register-password" class="auth-label block text-sm font-semibold"
              >密码</label
            >
            <input
              id="register-password"
              v-model="password"
              name="password"
              type="password"
              autocomplete="new-password"
              placeholder="设置密码"
              required
              aria-describedby="register-password-hint"
              :disabled="isSubmitting"
              class="auth-input"
            />
            <p id="register-password-hint" class="mt-2 text-xs leading-5 text-muted">
              {{ REGISTRATION_MESSAGES.passwordHint }}
            </p>
          </div>
        </div>
        <button
          type="submit"
          :disabled="isSubmitting"
          class="auth-primary flex w-full items-center justify-center gap-3 rounded-xl px-5 py-3.5 font-semibold"
        >
          {{ isSubmitting ? '正在注册…' : '创建账号' }}
          <span v-if="!isSubmitting" aria-hidden="true">→</span>
        </button>
        <p class="text-center text-sm text-muted">
          已有账号？<NuxtLink
            :to="AUTH_PATHS.login"
            class="ml-2 font-semibold text-accent underline underline-offset-4"
            >前往登录</NuxtLink
          >
        </p>
      </form>
    </UiSurfaceCard>
  </main>
</template>

<style src="../assets/css/auth-pages.css"></style>
