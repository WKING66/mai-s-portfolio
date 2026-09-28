<script setup lang="ts">
defineProps<{ id?: string; eyebrow: string; title: string; description?: string }>()
</script>

<template>
  <div class="mb-9 flex flex-col items-start justify-between gap-5 md:flex-row md:items-end">
    <div>
      <span class="eyebrow">{{ eyebrow }}</span>
      <h2 :id="id" class="mt-3 text-[clamp(2.1rem,5vw,4.5rem)] leading-none font-bold tracking-[-.055em]">{{ title }}</h2>
    </div>
    <p v-if="description" class="max-w-[480px] leading-7 text-muted">{{ description }}</p>
  </div>
</template>
