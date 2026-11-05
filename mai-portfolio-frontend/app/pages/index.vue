<script setup lang="ts">
import { computed } from 'vue'
import { HOME_INTRO, HOME_ABOUT } from '../constants/home'
import { BLOG_PATH, type BlogPage } from '../api/blogs'
import { BLOG_HOME_ARTICLE_COUNT, BLOG_PUBLIC_CACHE_PREFIX, BLOG_VIEW } from '../constants/blogs'
import type { ApiResponse } from '../api/types'
import type { AssistantContext } from '../utils/site/mockAssistant'
import { useSiteProfile } from '../api/profile'
import { SITE_MESSAGES } from '../constants/messages'
import { usePublicProjects } from '../api/projects'
import { HOME_PROJECT_LIMIT, PROJECT_MESSAGES } from '../constants/projects'
import { createProfileStructuredData, SEO_SITE_NAME } from '#shared/utils/seo'
import { useReadErrorFeedback } from '../composables/useFeedback'

// 1. 公开资料与项目并行读取；水合复用 SSR 快照，不等待账号会话恢复。
const [profileRequest, projectsRequest, articlesRequest] = await Promise.all([
  useSiteProfile(),
  usePublicProjects(1, HOME_PROJECT_LIMIT),
  useFetch<ApiResponse<BlogPage>>(BLOG_PATH, {
    key: BLOG_PUBLIC_CACHE_PREFIX + 'home',
    query: { view: BLOG_VIEW.PUBLIC, page: 1, size: BLOG_HOME_ARTICLE_COUNT },
  }),
])
const { data: profileResponse, error: profileError } = profileRequest
const siteProfile = computed(() =>
  profileResponse.value?.code === 'OK' ? profileResponse.value.data : null,
)
const { data: projectListResponse, error: projectsError } = projectsRequest
const featuredProjectPage = computed(() => projectListResponse.value?.data)
// 2. 两类读取失败分别展示，不能以空结果冒充请求成功。
useReadErrorFeedback(() => profileError.value, SITE_MESSAGES.profileLoadFailed)
useReadErrorFeedback(() => projectsError.value, PROJECT_MESSAGES.loadFailed)

// 3. SEO 与结构化数据只消费当前公开资料，部署域名仍由共用 SEO 能力校验。
const { siteOrigin } = usePortfolioSeo({
  title: () =>
    siteProfile.value ? siteProfile.value.displayName + ' · ' + HOME_INTRO : SEO_SITE_NAME,
  description: () => HOME_INTRO,
  canonicalPath: '/',
  available: () => !profileError.value && Boolean(siteProfile.value),
})
useHead(() => {
  const structuredData = createProfileStructuredData(
    siteOrigin,
    profileError.value || !siteProfile.value
      ? null
      : { ...siteProfile.value, headline: HOME_INTRO, intro: HOME_ABOUT },
  )
  return {
    script: structuredData
      ? [
          {
            key: 'portfolio-profile',
            type: 'application/ld+json',
            textContent: JSON.stringify(structuredData),
          },
        ]
      : [],
  }
})

const articles = computed(() => articlesRequest.data.value?.data?.items ?? [])
useReadErrorFeedback(() => articlesRequest.error.value, '文章读取失败，请重试。')
const assistantContext = computed<AssistantContext>(() => ({
  name: siteProfile.value?.displayName ?? '阿霾',
  sources: [
    {
      title: '关于' + (siteProfile.value?.displayName ?? '阿霾'),
      summary: HOME_INTRO + HOME_ABOUT,
      url: '/#about',
      kind: 'profile',
    },
    ...(featuredProjectPage.value?.items ?? []).map((project) => ({
      title: project.title,
      summary: project.summary,
      url: project.links[0]?.url ?? '/projects',
      kind: 'project' as const,
    })),
    ...articles.value
      .filter((article) => article.slug)
      .map((article) => ({
        title: article.title,
        summary: article.summary,
        url: '/blog/' + encodeURIComponent(article.slug!),
        kind: 'article' as const,
      })),
  ],
}))
const displayDate = useBlogDate()
</script>

<template>
  <main class="site-container home-page" data-home-content>
    <UButton
      v-if="profileError"
      class="my-10"
      color="neutral"
      variant="outline"
      @click="profileRequest.refresh()"
      >重新读取资料</UButton
    >
    <template v-else-if="siteProfile">
      <ProfileHero :profile="siteProfile" />
      <SiteHomeAssistant :context="assistantContext" />
      <aside class="home-now">
        <strong>● 最近在折腾</strong><span>完善这个个人站的博客与文档体验</span
        ><span>学习 Agent 的工具调用与对话流程</span>
      </aside>
      <section id="projects" class="home-section" aria-labelledby="projects-title">
        <div class="home-section-head">
          <div>
            <p class="home-label">01 / MAKING THINGS</p>
            <h2 id="projects-title">一些正在做的东西</h2>
            <p>从自己的小需求出发，把学到的知识接起来。</p>
          </div>
          <NuxtLink to="/projects">全部项目 ↗</NuxtLink>
        </div>
        <UButton
          v-if="projectsError"
          color="neutral"
          variant="outline"
          @click="projectsRequest.refresh()"
          >重新读取项目</UButton
        >
        <div v-else-if="featuredProjectPage?.items.length" class="home-projects">
          <ProjectCard
            v-for="project in featuredProjectPage.items"
            :key="project.id"
            :project="project"
            compact-on-mobile
            motion
          />
        </div>
        <p v-else class="home-empty">
          {{ SITE_MESSAGES.projectsEmpty }}
          <a
            v-if="siteProfile.githubUrl"
            :href="siteProfile.githubUrl"
            target="_blank"
            rel="noopener noreferrer"
            >去 GitHub 看看 ↗</a
          >
        </p>
      </section>
      <section id="blog" class="home-section home-notes" aria-labelledby="blog-title">
        <div>
          <p class="home-label">02 / LEARNING IN PUBLIC</p>
          <h2 id="blog-title">边学，边记。</h2>
          <p class="home-description">
            把暂时理解的东西写下来。既是给自己的备忘，也希望能帮到遇见同一个问题的人。
          </p>
          <NuxtLink to="/blog">查看全部文章 ↗</NuxtLink>
        </div>
        <div>
          <UButton
            v-if="articlesRequest.error.value"
            color="neutral"
            variant="outline"
            @click="articlesRequest.refresh()"
            >重新读取文章</UButton
          ><template v-else-if="articles.length">
            <NuxtLink
              v-for="(article, index) in articles"
              :key="article.id"
              :to="'/blog/' + encodeURIComponent(article.slug ?? '')"
              class="home-note"
            >
              <span class="home-note-number">{{ String(index + 1).padStart(2, '0') }}</span>
              <div class="home-note-copy">
                <h3>{{ article.title }}</h3>
                <p>{{ article.summary }}</p>
              </div>
              <time v-if="article.publishedAt" :datetime="article.publishedAt">{{
                displayDate(article.publishedAt)
              }}</time>
              <span class="home-note-arrow" aria-hidden="true">↗</span>
            </NuxtLink></template
          >
          <p v-else>文章尚未发布。</p>
        </div>
      </section>
      <section id="stack" class="home-section home-about" aria-labelledby="learning-title">
        <div>
          <p class="home-label">03 / A LITTLE ABOUT ME</p>
          <h2 id="learning-title">技术之外，也是普通学生。</h2>
          <p class="home-description">{{ HOME_ABOUT }}</p>
          <div v-if="siteProfile.techStack.length" class="home-tags">
            <span v-for="tag in siteProfile.techStack" :key="tag.slug">{{ tag.name }}</span>
          </div>
        </div>
        <aside>
          <p class="home-label">AWAY FROM THE KEYBOARD</p>
          <h3>写累了，就弹会儿电吉他。</h3>
          <p>代码之外，也会在宿舍插上静音耳放，练一会儿电吉他。这里希望保留一点这样的日常。</p>
        </aside>
      </section>
      <section id="contact" class="home-contact">
        <h2>保持联系。</h2>
        <p>如果你也在学习，或对某个小项目感兴趣，欢迎来聊聊。</p>
        <div>
          <a
            v-if="siteProfile.githubUrl"
            :href="siteProfile.githubUrl"
            target="_blank"
            rel="noopener noreferrer"
            >GitHub ↗</a
          ><a v-if="siteProfile.email" :href="'mailto:' + siteProfile.email">{{
            siteProfile.email
          }}</a>
        </div>
      </section>
    </template>
  </main>
</template>
<style scoped>
.home-page {
  max-width: 1120px;
}
/* 首页短栏目共用可视区中线定位，避免底部锚点都被钳制到同一个滚动位置。
   路由、首屏锚点恢复与导航高亮均读取这份 scroll-margin。 */
.home-page > section {
  scroll-margin-top: calc((100svh + var(--site-header-height)) / 2);
}
.home-now {
  display: flex;
  flex-wrap: wrap;
  gap: 16px 30px;
  padding: 18px 0;
  border-block: 1px solid var(--site-line);
  font-size: 12px;
  color: var(--site-muted);
}
.home-now strong {
  color: var(--site-accent);
}
.home-section {
  padding: 50px 0;
}
.home-section h2,
.home-contact h2 {
  font-size: 28px;
  font-weight: 650;
  letter-spacing: -1px;
  line-height: 1.4;
}
.home-label {
  font-size: 10px;
  color: var(--site-muted);
  letter-spacing: 1.7px;
  margin-bottom: 6px;
}
.home-section-head {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 25px;
  margin-bottom: 26px;
}
.home-section-head p:last-child {
  font-size: 13px;
  color: var(--site-muted);
  margin-top: 7px;
}
.home-section a,
.home-contact a {
  color: var(--site-accent);
}
.home-section-head > a {
  font-size: 12px;
  white-space: nowrap;
}
.home-projects {
  display: grid;
  gap: 28px;
}
.home-projects :deep(.project-card) {
  width: 100%;
  display: grid;
  grid-template-columns: 1fr 1.1fr;
  align-items: center;
}
.home-projects :deep(.project-card:not(:has(.project-cover))) {
  grid-template-columns: 1fr;
}
.home-projects :deep(.project-cover) {
  aspect-ratio: 1.5;
  border: 0;
  border-radius: 8px;
  margin-left: 25px;
}
.home-projects :deep(.project-card h3) {
  font-size: 27px;
}
.home-notes {
  display: grid;
  grid-template-columns: 230px 1fr;
  gap: 48px;
  border-top: 1px solid var(--site-line);
}
.home-description {
  color: var(--site-muted);
  font-size: 14px;
  line-height: 1.95;
  margin: 17px 0;
}
.home-notes > div > a {
  font-size: 12px;
}
.home-note {
  display: grid;
  grid-template-columns: 24px 1fr 85px 16px;
  gap: 18px;
  padding: 22px 12px;
  margin-inline: -12px;
  border-top: 1px solid var(--site-line);
  text-decoration: none;
  border-radius: 6px;
  transition: background-color 200ms ease;
}
.home-note:hover,
.home-note:focus-visible {
  background: color-mix(in srgb, var(--site-accent) 7%, transparent);
}
.home-note:focus-visible {
  outline: 2px solid var(--site-accent);
  outline-offset: 3px;
}
.home-note-copy {
  min-width: 0;
  transition: transform 200ms ease;
}
.home-note h3 {
  transition: color 200ms ease;
}
.home-note:hover .home-note-copy,
.home-note:focus-visible .home-note-copy {
  transform: translateX(4px);
}
.home-note:hover h3,
.home-note:focus-visible h3 {
  color: var(--site-accent);
}
.home-note-arrow {
  opacity: 0;
  transform: translate(-4px, 4px);
  transition:
    opacity 200ms ease,
    transform 200ms ease;
}
.home-note:hover .home-note-arrow,
.home-note:focus-visible .home-note-arrow {
  opacity: 1;
  transform: translate(0, 0);
}
.home-note:first-child {
  padding-top: 0;
  border: 0;
}
.home-note > span,
.home-note time {
  font-size: 10px;
  color: var(--site-muted);
}
.home-note h3 {
  font-size: 18px;
  font-weight: 600;
  color: var(--site-text);
}
.home-note p {
  font-size: 12px;
  color: var(--site-muted);
  line-height: 1.9;
  margin-top: 10px;
}
.home-about {
  display: grid;
  grid-template-columns: 1.3fr 1fr;
  gap: 70px;
  border-top: 1px solid var(--site-line);
}
.home-about aside {
  background: var(--site-bg-muted);
  padding: 28px;
  border-radius: 10px;
}
.home-about h3 {
  font-size: 21px;
  margin: 14px 0;
}
.home-about aside > p:last-child {
  color: var(--site-muted);
  font-size: 13px;
  line-height: 1.9;
}
.home-tags {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 11px;
}
.home-tags span {
  padding: 3px 9px;
  border: 1px solid var(--site-line);
  border-radius: 5px;
}
.home-contact {
  border-top: 1px solid var(--site-line);
  padding: 35px 0 60px;
}
.home-contact p {
  font-size: 14px;
  color: var(--site-muted);
  margin: 14px 0;
}
.home-contact > div {
  display: flex;
  flex-wrap: wrap;
  gap: 24px;
  font-size: 13px;
}
.home-empty {
  padding: 30px;
  border: 1px solid var(--site-line);
  border-radius: 10px;
  color: var(--site-muted);
}
@media (max-width: 700px) {
  .home-section {
    padding: 34px 0;
  }
  .home-notes,
  .home-about {
    grid-template-columns: 1fr;
    gap: 26px;
  }
  .home-note {
    grid-template-columns: 22px 1fr;
    gap: 12px;
  }
  .home-note time {
    grid-column: 2;
  }
  .home-note-arrow {
    display: none;
  }
  .home-projects {
    gap: 0;
    border-top: 1px solid var(--site-line);
  }
  .home-projects :deep(.project-card.project-card-compact) {
    display: block;
  }
  .home-projects :deep(.project-card-compact .project-cover) {
    margin: 0;
    aspect-ratio: auto;
  }
  .home-projects :deep(.project-card-compact h3) {
    font-size: 17px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .home-note,
  .home-note-copy,
  .home-note h3,
  .home-note-arrow {
    transition: none;
  }
  .home-note:hover .home-note-copy,
  .home-note:focus-visible .home-note-copy {
    transform: none;
  }
}
</style>
