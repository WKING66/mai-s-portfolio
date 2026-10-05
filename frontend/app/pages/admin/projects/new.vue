<script setup lang="ts">
import { AUTH_ACCESS, AUTH_MESSAGES } from '../../../constants/auth'
definePageMeta({ auth: AUTH_ACCESS.OWNER })
useSeoMeta({ title: '新增项目', robots: 'noindex,nofollow' })
</script>
<template>
  <Auth :access="AUTH_ACCESS.OWNER">
    <!-- 权限撤销会卸载编辑器，连同表单、标签及版本快照一起销毁。 -->
    <ProjectEditor />
    <template #pending><p role="status">{{ AUTH_MESSAGES.verifying }}</p></template>
  </Auth>
</template>
