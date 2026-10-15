<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useAuthState } from '../composables/useAuthState'
import { AUTH_ACCESS, type AuthAccess } from '../constants/auth'

// 保留 Nuxt 的 Auth 自动导入入口，仅明确内部组件名，便于调试定位职责。
defineOptions({ name: 'AccountAccess' })

const props = withDefaults(defineProps<{ access?: AuthAccess }>(), {
  access: AUTH_ACCESS.AUTHENTICATED,
})
const authState = useAuthState()
const isMounted = ref(false)
// SSR 与首轮水合只输出占位；挂载后再根据统一能力规则允许业务组件挂载。
onMounted(() => {
  isMounted.value = true
})
const isAccessAllowed = computed(() => isMounted.value && authState.canAccess(props.access))
// 只控制元素/子组件是否挂载，不发请求、不跳路由；实际请求仍须通过两端授权。
</script>

<template>
  <slot v-if="isAccessAllowed" />
  <slot v-else-if="!isMounted || !authState.ready.value" name="pending" />
</template>
