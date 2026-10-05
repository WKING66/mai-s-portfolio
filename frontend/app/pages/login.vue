<script setup lang="ts">
import { ref } from 'vue'
import { login } from '../api/session'
import { LOGIN_MESSAGES } from '../constants/messages'
import { useAuthState } from '../composables/useAuthState'
import { safeReturnPath } from '../api/permissions'

const config = useRuntimeConfig()
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
    if (auth.isOwner.value) await navigateTo(safeReturnPath(useRoute().query.returnTo))
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
  <main class="login-page">
    <form v-if="!loggedIn" class="login-card" @submit.prevent="submitLogin">
      <h1>登录</h1>
      <p>密码会先在浏览器中用部署配置中的固定公钥加密，再发送到后端。</p>
      <label for="username">用户名</label>
      <input id="username" v-model.trim="username" name="username" autocomplete="username" required>
      <label for="password">密码</label>
      <input id="password" v-model="password" name="password" type="password" autocomplete="current-password" required>
      <p v-if="errorMessage" role="alert" class="error">{{ errorMessage }}</p>
      <button type="submit" :disabled="pending">{{ pending ? '正在登录…' : '登录' }}</button>
    </form>
    <section v-else class="login-card" role="status">
      <h1>已登录</h1>
      <p>{{ username }}，你已登录。可访问的功能由账号权限决定。</p>
      <p v-if="errorMessage" role="alert" class="error">{{ errorMessage }}</p>
      <button type="button" :disabled="pending" @click="submitLogout">{{ pending ? '正在注销…' : '注销' }}</button>
      <a href="/">返回首页</a>
      <NuxtLink v-if="auth.isOwner.value" to="/admin/projects">管理项目</NuxtLink>
    </section>
  </main>
</template>

<style scoped>
.login-page { min-height: 100vh; display: grid; place-items: center; background: #0d1018; color: #f5f7fb; padding: 1.5rem; }
.login-card { width: min(100%, 26rem); display: grid; gap: .9rem; padding: 2rem; border: 1px solid #3d4960; border-radius: 1rem; background: #172030; }
h1 { margin: 0; }
p { margin: 0 0 .5rem; color: #becbe0; line-height: 1.5; }
label { font-weight: 600; }
input { width: 100%; padding: .75rem; border: 1px solid #73839b; border-radius: .5rem; background: #0d1018; color: inherit; font: inherit; }
input:focus-visible, button:focus-visible, a:focus-visible { outline: 2px solid #64baff; outline-offset: 2px; }
button { padding: .8rem; border: 0; border-radius: .5rem; background: #46adf2; color: #07121e; font: inherit; font-weight: 700; cursor: pointer; }
button:disabled { opacity: .6; cursor: wait; }
.error { color: #ffb7b7; }
a { color: #87caff; }
</style>
