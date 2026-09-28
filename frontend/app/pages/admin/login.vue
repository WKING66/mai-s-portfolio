<script setup lang="ts">
import { ref } from 'vue'
import { loginOwner } from '../../api/session'
import { LOGIN_MESSAGES } from '../../constants/messages'

const username = ref('owner')
const password = ref('')
const pending = ref(false)
const loggedIn = ref(false)
const errorMessage = ref('')

useSeoMeta({ title: '站长登录 · 阿霾作品集', robots: 'noindex,nofollow' })

async function submitLogin() {
  if (pending.value) return
  pending.value = true
  errorMessage.value = ''
  try {
    await loginOwner(username.value, password.value)
    password.value = ''
    loggedIn.value = true
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : LOGIN_MESSAGES.loginFailed
  } finally {
    pending.value = false
  }
}
</script>

<template>
  <main class="login-page">
    <form v-if="!loggedIn" class="login-card" @submit.prevent="submitLogin">
      <h1>站长登录</h1>
      <p>密码会先在浏览器中用一次性公钥加密，再发送到后端。</p>
      <label for="username">用户名</label>
      <input id="username" v-model.trim="username" name="username" autocomplete="username" required>
      <label for="password">密码</label>
      <input id="password" v-model="password" name="password" type="password" autocomplete="current-password" required>
      <p v-if="errorMessage" role="alert" class="error">{{ errorMessage }}</p>
      <button type="submit" :disabled="pending">{{ pending ? '正在登录…' : '登录' }}</button>
    </form>
    <section v-else class="login-card" role="status">
      <h1>已登录</h1>
      <p>站长会话已建立。管理页面将在对应功能模块完成后接入。</p>
      <a href="/">返回首页</a>
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
