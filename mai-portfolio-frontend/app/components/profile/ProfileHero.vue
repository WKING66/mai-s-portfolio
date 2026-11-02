<script setup lang="ts">
import type { SiteProfileResponse } from '../../api/profile'
import { HOME_INTRO } from '../../constants/home'
defineProps<{ profile: SiteProfileResponse }>()
</script>
<template>
  <section id="about" class="home-hero" aria-labelledby="profile-name">
    <div>
      <p class="home-eyebrow">A STUDENT, STILL LEARNING</p>
      <h1 id="profile-name">
        你好，我是{{ profile.displayName }}。<br />在学习，也在<em>做东西。</em>
      </h1>
      <p class="hero-copy">
        <strong>{{ HOME_INTRO }}</strong
        ><br />这里放着我写过的笔记、动手做的小项目，以及学习过程中留下的问题和思考。欢迎从这些小小的积累认识我。
      </p>
      <div class="hero-actions">
        <a href="#projects" class="hero-primary">看看我在做什么</a
        ><a
          v-if="profile.githubUrl"
          :href="profile.githubUrl"
          target="_blank"
          rel="noopener noreferrer"
          >在 GitHub 找到我 ↗</a
        >
      </div>
    </div>
    <aside class="profile-note">
      <img
        :src="profile.avatarUrl || '/images/home/avatar.jpg'"
        :alt="profile.displayName + '的头像'"
        width="130"
        height="130"
      />
      <p class="profile-name">{{ profile.displayName }}</p>
      <p class="profile-caption">写一点代码，记一些笔记。<br />屏幕之外，也会弹电吉他。</p>
    </aside>
  </section>
</template>
<style scoped>
.home-hero {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 270px;
  gap: 60px;
  align-items: center;
  padding: 64px 0 48px;
}
.home-eyebrow {
  font-size: 11px;
  letter-spacing: 1.5px;
  color: var(--site-accent);
}
h1 {
  font-size: clamp(40px, 4.6vw, 66px);
  line-height: 1.25;
  font-weight: 750;
  letter-spacing: -2.5px;
  margin: 21px 0 22px;
}
h1 em {
  font-style: normal;
  color: var(--site-accent);
}
.hero-copy {
  font-size: 15px;
  color: var(--site-muted);
  line-height: 2;
  max-width: 620px;
}
.hero-copy strong {
  color: var(--site-text);
  font-weight: 600;
}
.hero-actions {
  display: flex;
  gap: 25px;
  align-items: center;
  margin-top: 27px;
  font-size: 13px;
}
.hero-primary {
  padding: 11px 22px;
  border-radius: 28px;
  background: var(--site-accent);
  color: var(--site-inverted);
  font-weight: 600;
}
.profile-note {
  background: var(--site-bg-muted);
  border: 1px solid var(--site-line);
  border-radius: 100px 100px 12px 12px;
  padding: 30px;
  text-align: center;
  transform: rotate(2deg);
  transition: transform 0.3s;
}
.profile-note:hover {
  transform: rotate(0) translateY(-3px);
}
.profile-note img {
  width: 130px;
  height: 130px;
  border-radius: 50%;
  object-fit: cover;
  margin: 0 auto 20px;
}
.profile-name {
  font-size: 22px;
  font-weight: 650;
}
.profile-caption {
  font-size: 12px;
  color: var(--site-muted);
  margin-top: 12px;
  line-height: 1.9;
}
@media (max-width: 800px) {
  .home-hero {
    gap: 30px;
    grid-template-columns: 1fr 200px;
  }
  .profile-note {
    padding: 24px;
  }
}
@media (max-width: 600px) {
  .home-hero {
    display: flex;
    flex-direction: column-reverse;
    align-items: stretch;
    padding: 30px 0;
    gap: 24px;
  }
  .profile-note {
    transform: none;
    border: 0;
    background: none;
    display: flex;
    gap: 15px;
    align-items: center;
    padding: 0;
    text-align: left;
  }
  .profile-note img {
    width: 66px;
    height: 66px;
    margin: 0;
  }
  .profile-caption {
    display: none;
  }
  h1 {
    font-size: 32px;
    letter-spacing: -1.3px;
  }
  .hero-copy {
    font-size: 14px;
  }
  .hero-actions {
    gap: 18px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .profile-note {
    transition: none;
  }
}
</style>
