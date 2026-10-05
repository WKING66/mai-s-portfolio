<script setup lang="ts">
import { computed } from 'vue'
import { useAuthState } from '../composables/useAuthState'
import { AUTH_ACCESS, type AuthAccess } from '../constants/auth'

const props = withDefaults(defineProps<{ access?: AuthAccess }>(), {
  access: AUTH_ACCESS.AUTHENTICATED,
})
const auth = useAuthState()
const allowed = computed(() => auth.canAccess(props.access))
// 只控制元素/子组件是否挂载，不发请求、不跳路由；实际请求仍须通过两端授权。
</script>

<template>
  <slot v-if="allowed" />
  <slot v-else-if="!auth.ready.value" name="pending" />
</template>
