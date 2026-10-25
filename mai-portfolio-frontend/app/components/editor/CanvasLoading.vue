<script setup lang="ts">
defineProps<{ loaded?: number; total?: number; phase?: string }>()
</script>

<template>
  <section class="canvas-loading" role="status" aria-live="polite" aria-busy="true">
    <div class="loading-label">
      <span class="loading-spinner" aria-hidden="true" />
      <span>{{ phase || '正在加载编辑器' }}</span>
      <span v-if="total && loaded !== undefined" class="loading-count"
        >{{ loaded }} / {{ total }}</span
      >
    </div>
    <progress
      :value="total ? loaded : undefined"
      :max="total || undefined"
      aria-label="编辑器加载进度"
    />
    <div class="loading-page" aria-hidden="true">
      <div class="loading-toc"><i /><i /><i /><i /></div>
      <div class="loading-article">
        <i class="loading-title" /><i /><i /><i /><i class="loading-short" />
      </div>
    </div>
  </section>
</template>

<style scoped>
.canvas-loading {
  width: 100%;
  min-height: 22rem;
  padding: clamp(1rem, 3vw, 2.5rem);
  background: var(--site-bg);
  color: var(--ui-text-muted);
}
.loading-label {
  display: flex;
  align-items: center;
  gap: 0.65rem;
  font-size: 0.875rem;
}
.loading-count {
  margin-inline-start: auto;
  font-variant-numeric: tabular-nums;
}
progress {
  width: 100%;
  height: 0.3rem;
  margin-block: 1rem 2rem;
  accent-color: var(--site-accent);
}
.loading-spinner {
  width: 1rem;
  height: 1rem;
  border: 2px solid var(--ui-border);
  border-top-color: var(--site-accent);
  border-radius: 50%;
  animation: loading-spin 1s linear infinite;
}
.loading-page {
  display: flex;
  gap: 2.5rem;
}
.loading-toc {
  width: 20%;
  display: none;
}
.loading-article {
  flex: 1;
}
i {
  display: block;
  height: 0.8rem;
  margin-block: 1.1rem;
  border-radius: 0.25rem;
  background: var(--ui-bg-accented);
}
.loading-title {
  height: 2rem;
  width: 80%;
  margin-bottom: 2rem;
}
.loading-short {
  width: 60%;
}
@media (min-width: 768px) {
  .loading-toc {
    display: block;
  }
}
@media (prefers-reduced-motion: reduce) {
  .loading-spinner {
    animation: none;
  }
}
@keyframes loading-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
