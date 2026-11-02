<script setup lang="ts">
import { useId } from 'vue'

defineProps<{ title: string; description?: string; plain?: boolean }>()
const headingId = useId()
</script>

<template>
  <section class="min-w-0" :aria-labelledby="headingId">
    <div v-if="plain" class="grid gap-4">
      <div>
        <h2 :id="headingId" class="text-base font-semibold">{{ title }}</h2>
        <p v-if="description" class="mt-1 text-sm leading-6 text-muted">{{ description }}</p>
      </div>
      <slot />
    </div>
    <UCard v-else>
      <template #header>
        <h2 :id="headingId" class="text-lg font-semibold">{{ title }}</h2>
        <p v-if="description" class="mt-1 text-sm leading-6 text-muted">{{ description }}</p>
      </template>
      <slot />
    </UCard>
  </section>
</template>
