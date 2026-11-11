<script setup lang="ts">
import AgentSettingsEditor from '../../components/admin/AgentSettingsEditor.vue'
import { AUTH_ACCESS } from '../../constants/auth'

definePageMeta({ auth: AUTH_ACCESS.OWNER })
useSeoMeta({ title: '数字人配置中心', robots: 'noindex,nofollow' })
</script>

<template>
  <AdminShell title="数字人配置中心" description="统一管理回答风格、模型参数和费用上限。">
    <AgentSettingsEditor />
  </AdminShell>
</template>
