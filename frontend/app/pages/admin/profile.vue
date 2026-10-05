<script setup lang="ts">
import { AUTH_ACCESS, AUTH_MESSAGES } from '../../constants/auth'
import { PROFILE_MESSAGES } from '../../constants/profile'

definePageMeta({ auth: AUTH_ACCESS.OWNER })
useSeoMeta({ title: PROFILE_MESSAGES.title, robots: 'noindex,nofollow' })
</script>

<template>
  <Auth :access="AUTH_ACCESS.OWNER">
    <!-- 鉴权完成后才挂载编辑器；失去权限时销毁本地管理快照。 -->
    <ProfileEditor />
    <template #pending><p role="status">{{ AUTH_MESSAGES.verifying }}</p></template>
  </Auth>
</template>
