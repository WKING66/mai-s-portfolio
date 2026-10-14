<script setup lang="ts">
withDefaults(defineProps<{ as?: 'article' | 'aside' | 'div'; motion?: boolean }>(), { as: 'article', motion: false })
</script>

<template>
  <component :is="as" class="surface-card glass-surface" :data-motion="motion || undefined"><slot /></component>
</template>

<style scoped>
.surface-card { min-width: 0; border-radius: var(--site-radius); transition: border-color .18s, background-color .18s; }
.surface-card:hover { border-color: var(--site-border-accented); }
.surface-card[data-motion] { transition: transform .22s ease, border-color .18s, background-color .18s; }
@media (hover: hover) and (pointer: fine) and (prefers-reduced-motion: no-preference) {
  .surface-card[data-motion]:is(:hover, :focus-within) { transform: translateY(-3px); }
}
</style>
