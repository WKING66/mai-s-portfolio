<script setup lang="ts">
import type { SiteProfileResponse } from '../../api/profile'
import { SITE_MESSAGES } from '../../constants/messages'

defineProps<{ profile: SiteProfileResponse }>()
</script>

<template>
  <section id="about" class="py-12 md:py-16" aria-labelledby="profile-name">
    <div class="min-w-0">
      <div class="flex flex-wrap items-center gap-x-6 gap-y-3">
        <h1
          id="profile-name"
          class="m-0 text-[clamp(3rem,5vw,4.2rem)] leading-none font-bold tracking-[-.055em]"
        >
          {{ profile.displayName }}
        </h1>
        <span class="availability inline-flex items-center gap-2.5 rounded-full px-3 py-1.5 text-xs"
          >关注 Java 后端与 AI 智能体开发</span
        >
        <img
          v-if="profile.avatarUrl"
          :src="profile.avatarUrl"
          :alt="`${profile.displayName}的头像`"
          class="size-12 rounded-full object-cover"
        />
      </div>
      <p
        class="hero-role mt-7 mb-5 text-[clamp(1.3rem,2.2vw,1.7rem)] font-medium tracking-[-.03em]"
      >
        {{ profile.headline }}
      </p>
      <p
        class="max-w-[760px] whitespace-pre-line text-[clamp(1rem,1.4vw,1.1rem)] leading-[1.85] text-muted"
      >
        {{ profile.intro }}
      </p>
      <div class="mt-6 flex flex-wrap gap-3" aria-label="简历与联系方式">
        <a v-if="profile.resumeUrl" class="hero-shortcut resume" :href="profile.resumeUrl">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M5 2h9l5 5v6M5 2v20h8M14 2v5h5M16 18l2 2 4-4" /></svg
          >Resume
        </a>
        <span
          v-else
          class="hero-shortcut resume unavailable"
          aria-disabled="true"
          :title="SITE_MESSAGES.resumeUnavailable"
        >
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M5 2h9l5 5v6M5 2v20h8M14 2v5h5M16 18l2 2 4-4" /></svg
          >Resume
        </span>
        <a
          v-if="profile.githubUrl"
          class="hero-shortcut"
          :href="profile.githubUrl"
          target="_blank"
          rel="noopener noreferrer"
        >
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path
              d="M9 19c-4 1-4-2-6-2m12 4v-3a3 3 0 0 0-.8-2.3c2.7-.3 5.6-1.3 5.6-6A4.7 4.7 0 0 0 18.5 6 4.3 4.3 0 0 0 18.4 3S17.3 2.7 15 4.2a12 12 0 0 0-6 0C6.7 2.7 5.6 3 5.6 3A4.3 4.3 0 0 0 5.5 6a4.7 4.7 0 0 0-1.3 3.7c0 4.7 2.9 5.7 5.6 6A3 3 0 0 0 9 18v3"
            /></svg
          >GitHub
        </a>
        <a v-if="profile.email" class="hero-shortcut" :href="`mailto:${profile.email}`">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <rect x="2.5" y="5" width="19" height="14" rx="2" />
            <path d="m3 7 9 7 9-7" /></svg
          >Email
        </a>
      </div>
      <div class="mt-5 flex flex-wrap gap-x-6 gap-y-2 text-sm text-muted">
        <a class="underline decoration-accent underline-offset-4 hover:text-accent" href="#projects"
          >查看项目 →</a
        >
        <a class="underline decoration-accent underline-offset-4 hover:text-accent" href="#blog"
          >阅读博客 →</a
        >
      </div>
    </div>
    <SiteTechStack :items="profile.techStack" />
  </section>
</template>

<style scoped>
.hero-role {
  color: var(--site-accent);
}
.availability {
  border: 1px solid color-mix(in srgb, var(--site-accent-secondary) 50%, transparent);
  background: color-mix(in srgb, var(--site-accent-secondary) 10%, transparent);
}
.availability::before {
  content: '';
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--site-accent-secondary);
}
.hero-shortcut {
  min-height: 40px;
  display: inline-flex;
  align-items: center;
  gap: 9px;
  padding: 0 13px;
  border: 1px solid color-mix(in srgb, var(--site-accent) 48%, var(--site-line));
  border-radius: var(--site-radius);
  background: var(--site-surface);
  color: var(--site-text);
  text-decoration: none;
  font-size: 0.9rem;
  font-weight: 600;
  transition:
    background-color 0.2s,
    border-color 0.2s;
}
a.hero-shortcut:hover {
  border-color: var(--site-accent);
  background: var(--site-bg-muted);
}
.hero-shortcut.resume {
  border-color: transparent;
  background: var(--site-accent);
  color: var(--site-inverted);
}
a.hero-shortcut.resume:hover {
  background: var(--site-primary-hover);
}
.hero-shortcut.unavailable {
  opacity: 0.72;
  cursor: not-allowed;
}
.hero-shortcut svg {
  flex: none;
  width: 18px;
  height: 18px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
}
@media (max-width: 520px) {
  .hero-shortcut {
    padding-inline: 11px;
    font-size: 0.9rem;
  }
}
@media (prefers-reduced-motion: reduce) {
  .hero-shortcut {
    transition: none;
  }
  a.hero-shortcut:hover {
    transform: none;
  }
}
</style>
