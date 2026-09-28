<script setup lang="ts">
withDefaults(defineProps<{
  as?: 'article' | 'aside' | 'div'
  maxTilt?: number
}>(), { as: 'article', maxTilt: 10 })

function onPointerMove(event: PointerEvent, maxTilt: number) {
  if (!window.matchMedia('(hover: hover) and (pointer: fine)').matches
    || window.matchMedia('(prefers-reduced-motion: reduce)').matches) return

  const card = event.currentTarget as HTMLElement
  const box = card.getBoundingClientRect()
  const x = (event.clientX - box.left) / box.width
  const y = (event.clientY - box.top) / box.height
  card.style.setProperty('--tilt-x', `${(0.5 - y) * maxTilt}deg`)
  card.style.setProperty('--tilt-y', `${(x - 0.5) * maxTilt}deg`)
  card.style.setProperty('--spot-x', `${x * 100}%`)
  card.style.setProperty('--spot-y', `${y * 100}%`)
  card.style.setProperty('--glow', '1')
}

function onPointerLeave(event: PointerEvent) {
  const card = event.currentTarget as HTMLElement
  for (const property of ['--tilt-x', '--tilt-y', '--spot-x', '--spot-y', '--glow']) {
    card.style.removeProperty(property)
  }
}
</script>

<template>
  <component
    :is="as"
    class="surface-card glass-surface"
    @pointermove="onPointerMove($event, maxTilt)"
    @pointerleave="onPointerLeave"
  >
    <slot />
  </component>
</template>

<style scoped>
.surface-card {
  position: relative;
  isolation: isolate;
  border-radius: 24px;
  background-image: linear-gradient(105deg, transparent 28%, color-mix(in srgb, var(--site-accent) 10%, transparent) 47%, transparent 66%);
  background-size: 240% 100%;
  animation: surface-flow 9s linear infinite;
  transform: perspective(900px) rotateX(var(--tilt-x, 0deg)) rotateY(var(--tilt-y, 0deg));
  transition: transform .28s ease, box-shadow .28s ease, border-color .28s ease;
}
.surface-card::before {
  content: "";
  position: absolute;
  inset: 0;
  z-index: -1;
  border-radius: inherit;
  pointer-events: none;
  opacity: var(--glow, 0);
  background: radial-gradient(360px circle at var(--spot-x, 50%) var(--spot-y, 50%), color-mix(in srgb, var(--site-accent) 42%, transparent), transparent 72%);
  transition: opacity .25s ease;
}
.surface-card:hover {
  transform: perspective(900px) translateY(-4px) rotateX(var(--tilt-x, 0deg)) rotateY(var(--tilt-y, 0deg));
  border-color: color-mix(in srgb, var(--site-accent) 50%, var(--site-line));
  box-shadow: 0 25px 70px color-mix(in srgb, var(--site-accent) 17%, transparent);
}
@keyframes surface-flow {
  from { background-position: 100% 0; }
  to { background-position: -100% 0; }
}
@media (prefers-reduced-motion: reduce) {
  .surface-card { animation: none; transition: none; transform: none; }
  .surface-card:hover { transform: none; }
  .surface-card::before { display: none; }
}
</style>
