<script setup lang="ts">
import { computed } from 'vue'
import { usePublicProfile } from '../api/profile'
import { SITE_MESSAGES } from '../constants/messages'

const { data: response, error } = await usePublicProfile()
const profile = computed(() => response.value?.code === 'OK' ? response.value.data : null)

const focusAreas = [
  { index: '01 / ENGINEERING', title: '工具权限', description: '关注 AI 智能体调用工具时的权限边界与可控性。' },
  { index: '02 / EFFICIENCY', title: 'Token 预算', description: '在效果与成本之间寻找可度量、可复盘的平衡。' },
  { index: '03 / EVALUATION', title: '性能验证', description: '不只追求看起来更好，更要证明改进确实发生。' },
]

useSeoMeta({
  title: '阿霾 · Java 后端与 AI 智能体开发',
  description: '阿霾的个人作品集，展示 Java 后端、AI 智能体开发方向的项目、技术实践与博客。',
})
</script>

<template>
  <div class="min-h-screen font-sans text-ink">
    <SiteHeader />
    <main class="mx-auto w-full max-w-[1200px] px-4 sm:px-5">
      <p v-if="error" class="mt-10 rounded-2xl border border-accent/40 p-6" role="status">
        {{ SITE_MESSAGES.profileLoadFailed }}
      </p>
      <template v-else-if="profile">
        <ProfileHero :profile="profile" />

        <section class="section-space border-t border-line" aria-labelledby="focus-title">
          <SectionHeading id="focus-title" eyebrow="02 / FOCUS" title="我关注的问题"
            description="一次大语言模型项目复盘，促使我把注意力放在更扎实的工程问题上。" />
          <div class="grid gap-[18px] md:grid-cols-12">
            <SurfaceCard v-for="(area, index) in focusAreas" :key="area.index"
              class="focus-card flex min-h-[230px] flex-col justify-between p-7"
              :class="index === 0 ? 'md:col-span-5' : index === 1 ? 'md:col-span-4' : 'md:col-span-3'">
              <small class="font-mono text-xs font-bold tracking-wider text-accent">{{ area.index }}</small>
              <div>
                <h3 class="mb-2 text-[1.6rem] font-semibold tracking-tight">{{ area.title }}</h3>
                <p class="leading-7 text-muted">{{ area.description }}</p>
              </div>
            </SurfaceCard>
          </div>
        </section>

        <section id="projects" class="section-space border-t border-line" aria-labelledby="projects-title">
          <SectionHeading id="projects-title" eyebrow="03 / SELECTED WORK" title="项目作品"
            description="项目会以介绍、技术标签、图片和外部入口呈现，不会上传整个项目文件。" />
          <SurfaceCard class="project-placeholder grid min-h-[330px] overflow-hidden p-7 md:grid-cols-[1fr_.8fr] md:gap-8 md:p-8">
            <div class="flex flex-col justify-end">
              <span class="mb-4 w-fit rounded-full border border-line px-3 py-1 text-xs text-muted">即将更新</span>
              <h3 class="mb-3 text-[clamp(1.7rem,3vw,2.6rem)] font-semibold tracking-tight">真实项目，逐个呈现。</h3>
              <p class="max-w-[480px] leading-7 text-muted">{{ SITE_MESSAGES.projectsEmpty }}</p>
              <a v-if="profile.githubUrl" :href="profile.githubUrl" target="_blank" rel="noopener noreferrer"
                class="mt-6 w-fit text-sm font-bold text-accent underline underline-offset-4">先看看我的 GitHub ↗</a>
            </div>
            <div class="project-visual relative mt-8 min-h-[170px] overflow-hidden rounded-2xl md:mt-0" aria-hidden="true">
              <span class="absolute right-4 bottom-0 text-[clamp(7rem,17vw,13rem)] leading-none font-black text-accent/15">01</span>
            </div>
          </SurfaceCard>
        </section>

        <section id="blog" class="section-space border-t border-line" aria-labelledby="blog-title">
          <SectionHeading id="blog-title" eyebrow="04 / JOURNAL" title="博客笔记"
            description="记录技术实践、项目复盘与持续学习；正式文章发布后在这里展示。" />
          <div class="grid grid-cols-[1fr_auto] items-center gap-6 border-y border-line py-7 sm:grid-cols-[110px_1fr_auto]">
            <span class="hidden font-mono text-xs text-muted sm:block">JOURNAL</span>
            <p class="text-lg font-medium">{{ SITE_MESSAGES.blogEmpty }}</p>
            <span class="whitespace-nowrap text-sm text-muted">待发布</span>
          </div>
        </section>

        <section id="contact" class="section-space border-t border-line" aria-labelledby="contact-title">
          <SurfaceCard class="contact-hub p-7 sm:p-10" :max-tilt="5">
            <span class="eyebrow">05 / CONTACT</span>
            <h2 id="contact-title" class="mt-3 text-[clamp(2.1rem,5vw,4.2rem)] leading-tight font-bold tracking-[-.05em]">聊聊你的想法。</h2>
            <p class="mt-4 max-w-[620px] leading-7 text-muted">{{ SITE_MESSAGES.contactInvitation }}</p>
            <div class="mt-7 flex flex-wrap gap-3">
              <a v-if="profile.email" :href="`mailto:${profile.email}`" class="contact-link">✉ {{ profile.email }}</a>
              <a v-if="profile.githubUrl" :href="profile.githubUrl" target="_blank" rel="noopener noreferrer" class="contact-link">↗ GitHub / WKING66</a>
            </div>
          </SurfaceCard>
        </section>
      </template>
    </main>
    <footer class="border-t border-line py-10 text-sm text-muted">
      <div class="mx-auto flex max-w-[1200px] flex-wrap justify-between gap-3 px-4 sm:px-5">
        <span>© {{ new Date().getFullYear() }} 阿霾 · 个人作品集</span>
        <span class="text-xs">
          图标鸣谢：<a href="https://git-scm.com/community/logos" target="_blank" rel="noopener noreferrer" class="underline underline-offset-2">Git / Jason Long</a> ·
          <a href="https://www.jenkins.io/artwork/" target="_blank" rel="noopener noreferrer" class="underline underline-offset-2">Jenkins Project</a>
        </span>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.section-space { padding-block: 110px; scroll-margin-top: 70px; }
.project-visual {
  background:
    radial-gradient(circle at 76% 32%, color-mix(in srgb, var(--site-accent-secondary) 58%, transparent), transparent 27%),
    linear-gradient(125deg, color-mix(in srgb, var(--site-accent) 30%, transparent), color-mix(in srgb, var(--site-accent-secondary) 13%, transparent));
}
.contact-link { padding: 11px 16px; border: 1px solid var(--site-line); border-radius: 999px; text-decoration: none; transition: border-color .2s ease, transform .2s ease; }
.contact-link:hover { border-color: var(--site-accent); transform: translateY(-2px); }
@media (max-width: 720px) { .section-space { padding-block: 78px; } }
@media (prefers-reduced-motion: reduce) { .contact-link { transition: none; } .contact-link:hover { transform: none; } }
</style>
