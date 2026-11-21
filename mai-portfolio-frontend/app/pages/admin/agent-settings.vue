<script setup lang="ts">
import AgentSettingsEditor from '../../components/admin/AgentSettingsEditor.vue'
import { AUTH_ACCESS } from '../../constants/auth'

definePageMeta({ auth: AUTH_ACCESS.OWNER })
useSeoMeta({ title: '数字人配置中心', robots: 'noindex,nofollow' })
</script>

<template>
  <AdminShell title="数字人配置中心" description="管理模型接入、执行顺序与数字人的回答风格。">
    <AgentSettingsEditor />
  </AdminShell>
</template>
