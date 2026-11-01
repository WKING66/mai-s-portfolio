<script setup lang="ts">
import { AUTH_ACCESS } from '../../../constants/auth'
definePageMeta({ auth: AUTH_ACCESS.OWNER })
useSeoMeta({ title: '新建项目', robots: 'noindex,nofollow' })
</script>
<template><ProjectEditorModal /></template>
