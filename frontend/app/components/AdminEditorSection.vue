<script setup lang="ts">
import { useId } from 'vue'

defineProps<{ title: string; description?: string }>()
const headingId = useId()
</script>

<template>
  <section :aria-labelledby="headingId">
    <UCard>
      <template #header>
        <h2 :id="headingId" class="text-lg font-semibold">{{ title }}</h2>
        <p v-if="description" class="mt-1 text-sm leading-6 text-muted">{{ description }}</p>
      </template>
      <slot />
    </UCard>
  </section>
</template>
