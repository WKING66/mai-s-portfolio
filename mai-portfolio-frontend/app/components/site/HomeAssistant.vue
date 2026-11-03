<script setup lang="ts">
import type { AssistantContext } from '../../utils/site/mockAssistant'
import { useMockSiteAssistant } from '../../composables/useMockSiteAssistant'

const props = defineProps<{ context: AssistantContext }>()
const { turns, generating, latest, send, stop, clear } = useMockSiteAssistant(() => props.context)
const dialog = useTemplateRef<HTMLDialogElement>('drawer')
const log = useTemplateRef<HTMLElement>('log')
const input = useTemplateRef<HTMLInputElement>('input')
const draft = ref('')
const homeQuestion = ref('')
const invitation = useTemplateRef<HTMLElement>('invitation')
const inviting = ref(false)
let invitationObserver: IntersectionObserver | undefined

function dismissInvitation() {
  inviting.value = false
  invitationObserver?.disconnect()
}
const quote = ref('')
const selectedText = ref('')
const tools = ref<{ x: number; y: number } | null>(null)
const pipelineOpen = ref(false)
const selectedTurn = ref<number | null>(null)
const drawerWidth = ref(940)
const follow = ref(true)
const current = computed(
  () => turns.value.find((turn) => turn.id === selectedTurn.value) ?? latest.value,
)
let previousOverflow = ''
let opener: HTMLElement | null = null
let selectionFrame = 0

function open() {
  dismissInvitation()
  if (!dialog.value?.open) {
    opener = document.activeElement instanceof HTMLElement ? document.activeElement : null
    previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    dialog.value?.showModal()
  }
  input.value?.focus()
}
function close() {
  dialog.value?.close()
}
function afterClose() {
  stop()
  document.body.style.overflow = previousOverflow
  tools.value = null
  opener?.focus({ preventScroll: true })
}
function jumpToLatest() {
  follow.value = true
  log.value?.scrollTo({ top: log.value.scrollHeight })
}
function trackScroll() {
  const element = log.value
  if (element) {
    follow.value = element.scrollHeight - element.clientHeight - element.scrollTop < 70
  }
}
async function submit(question = draft.value) {
  if (!question.trim() || generating.value) {
    return
  }
  open()
  const contextQuote = quote.value
  draft.value = ''
  homeQuestion.value = ''
  quote.value = ''
  follow.value = true
  selectedTurn.value = null
  await send(question, contextQuote)
}
watch(
  () => latest.value?.content,
  async () => {
    await nextTick()
    if (follow.value) {
      jumpToLatest()
    }
  },
)
function clearConversation() {
  clear()
  quote.value = ''
  selectedTurn.value = null
}
function setWidth(value: number) {
  drawerWidth.value = Math.min(window.innerWidth, Math.max(420, value))
}
function resizeMove(event: PointerEvent) {
  if ((event.currentTarget as HTMLElement).hasPointerCapture(event.pointerId)) {
    setWidth(window.innerWidth - event.clientX)
  }
}
function resizeStart(event: PointerEvent) {
  if (event.button === 0) {
    event.preventDefault()
    ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
  }
}
function resizeKey(event: KeyboardEvent) {
  if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
    event.preventDefault()
    setWidth(
      (dialog.value?.getBoundingClientRect().width ?? drawerWidth.value) +
        (event.key === 'ArrowLeft' ? 40 : -40),
    )
  }
}
function inspectSelection() {
  cancelAnimationFrame(selectionFrame)
  selectionFrame = requestAnimationFrame(() => {
    const selection = window.getSelection()
    if (!selection || selection.isCollapsed || !selection.rangeCount) {
      tools.value = null
      return
    }
    const range = selection.getRangeAt(0)
    const node = range.commonAncestorContainer
    const element = node instanceof Element ? node : node.parentElement
    if (
      !element?.closest('[data-home-content], .assistant-log') ||
      element.closest('button,input,textarea')
    ) {
      tools.value = null
      return
    }
    selectedText.value = selection.toString().trim().slice(0, 1000)
    const rect = range.getBoundingClientRect()
    tools.value = selectedText.value
      ? {
          x: Math.max(8, Math.min(rect.left, innerWidth - 290)),
          y: Math.max(8, Math.min(rect.bottom + 8, innerHeight - 60)),
        }
      : null
  })
}
function selectAction(action: string) {
  quote.value = selectedText.value
  tools.value = null
  window.getSelection()?.removeAllRanges()
  open()
  if (action !== '就这段提问') {
    void submit(action)
  }
}
function onPointerUp(event: PointerEvent) {
  if (!(event.target instanceof Element) || !event.target.closest('.selection-tools')) {
    inspectSelection()
  }
}
function hideTools() {
  tools.value = null
}
function onResize() {
  setWidth(drawerWidth.value)
  hideTools()
}
onMounted(() => {
  pipelineOpen.value = innerWidth > 1000
  // 只在首次进入视野时提示两次；交互后不再打扰，不注册循环定时器。
  if (!matchMedia('(prefers-reduced-motion: reduce)').matches && invitation.value) {
    invitationObserver = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          inviting.value = true
          invitationObserver?.disconnect()
        }
      },
      { threshold: 0.4 },
    )
    invitationObserver.observe(invitation.value)
  }
  document.addEventListener('pointerup', onPointerUp)
  document.addEventListener('scroll', hideTools, true)
  window.addEventListener('resize', onResize)
})
onBeforeUnmount(() => {
  invitationObserver?.disconnect()
  cancelAnimationFrame(selectionFrame)
  document.removeEventListener('pointerup', onPointerUp)
  document.removeEventListener('scroll', hideTools, true)
  window.removeEventListener('resize', onResize)
  if (dialog.value?.open) {
    document.body.style.overflow = previousOverflow
  }
})
</script>

<template>
  <section
    ref="invitation"
    class="home-ask"
    :class="{ 'is-inviting': inviting }"
    aria-labelledby="home-ask-title"
    @focusin="dismissInvitation"
    @pointerdown="dismissInvitation"
  >
    <div>
      <p class="assistant-kicker">● AMAI / AI GUIDE · MOCK</p>
      <h2 id="home-ask-title">从一个问题开始，认识我。</h2>
      <p class="text-muted text-sm">关于我的项目、笔记和学习路线，问问站内向导。</p>
    </div>
    <div>
      <form
        class="assistant-form"
        @submit.prevent="submit(homeQuestion)"
        @animationend.self="dismissInvitation"
      >
        <input
          v-model="homeQuestion"
          aria-label="向 AI 提问"
          placeholder="这个个人站是怎么做出来的？"
          maxlength="300"
          required
        /><button aria-label="发送首页问题" :disabled="generating">↗</button>
      </form>
      <div class="assistant-suggestions">
        <button
          v-for="question in ['介绍一下站主', '做过哪些项目？', '推荐一篇文章']"
          :key="question"
          :disabled="generating"
          @click="submit(question)"
        >
          {{ question }}
        </button>
      </div>
    </div>
  </section>
  <button class="assistant-launch" @click="open">
    <UIcon name="i-lucide-message-circle" />问问 AI ↗
  </button>
  <Teleport to="body">
    <div
      v-if="tools && !dialog?.open"
      class="selection-tools"
      role="toolbar"
      aria-label="选区 AI 操作"
      :style="{ left: tools.x + 'px', top: tools.y + 'px' }"
      @pointerdown.prevent
    >
      <button
        v-for="action in ['解释这段', '展开讲讲', '就这段提问']"
        :key="action"
        @click="selectAction(action)"
      >
        {{ action }}
      </button>
    </div>
    <dialog
      ref="drawer"
      class="assistant-drawer"
      :class="{ 'with-pipeline': pipelineOpen, 'narrow-drawer': drawerWidth < 800 }"
      :style="{ width: drawerWidth + 'px' }"
      aria-labelledby="assistant-title"
      @close="afterClose"
      @cancel="hideTools"
    >
      <div
        role="separator"
        tabindex="0"
        aria-label="调整聊天抽屉宽度"
        aria-orientation="vertical"
        :aria-valuenow="drawerWidth"
        :aria-valuemin="420"
        class="assistant-resize"
        @pointerdown="resizeStart"
        @pointermove="resizeMove"
        @keydown="resizeKey"
        @dblclick="drawerWidth = 940"
      />
      <div class="assistant-chat">
        <header class="assistant-head">
          <div>
            <strong id="assistant-title">{{ context.name }}的 AI 向导</strong
            ><small>公开资料 · Mock 演示</small>
          </div>
          <div class="assistant-head-actions">
            <button :aria-expanded="pipelineOpen" @click="pipelineOpen = !pipelineOpen">
              {{ pipelineOpen ? '收起管线' : '展开管线' }}</button
            ><button @click="clearConversation">清空</button
            ><button aria-label="关闭 AI 向导" @click="close">×</button>
          </div>
        </header>
        <div ref="log" class="assistant-log" role="log" aria-label="AI 对话" @scroll="trackScroll">
          <div class="assistant-welcome">
            <span>✳</span>
            <h2>你好，想了解什么？</h2>
            <p>回答依据当前公开项目、文章摘要和个人介绍，点击来源可以继续阅读。</p>
          </div>
          <article v-for="turn in turns" :key="turn.id" class="assistant-turn">
            <div class="assistant-question">
              <blockquote v-if="turn.quote">{{ turn.quote }}</blockquote>
              {{ turn.question }}
            </div>
            <div class="assistant-answer">
              <small>✳ AI 向导</small>
              <p class="stream-content">
                {{ turn.content
                }}<span
                  v-if="turn.status === 'generating'"
                  class="stream-caret"
                  aria-hidden="true"
                />
              </p>
              <p v-if="turn.status === 'stopped'" class="assistant-status">
                已停止生成，已输出内容保留。
              </p>
              <p v-if="turn.status === 'error'" class="assistant-status">回复未完成，请重试。</p>
              <template v-if="turn.status === 'complete'"
                ><a
                  v-for="source in turn.sources"
                  :key="source.url"
                  :href="source.url"
                  class="assistant-source"
                  :target="source.url.startsWith('http') ? '_blank' : undefined"
                  rel="noopener noreferrer"
                  @click="!source.url.startsWith('http') && close()"
                  >{{ source.title }} ↗</a
                ></template
              >
            </div>
          </article>
        </div>
        <button v-if="!follow" class="latest-button" @click="jumpToLatest">回到最新回复 ↓</button>
        <div class="assistant-bottom">
          <div v-if="quote" class="assistant-quote">
            <span>{{ quote }}</span
            ><button aria-label="移除引用" @click="quote = ''">×</button>
          </div>
          <button v-if="generating" class="stop-button" @click="stop">■ 停止生成</button>
          <form class="assistant-form" @submit.prevent="submit()">
            <input
              ref="input"
              v-model="draft"
              aria-label="继续提问"
              :placeholder="quote ? '关于所选内容，你想了解什么？' : '问问项目、文章或学习路线…'"
              maxlength="300"
              required
            /><button aria-label="发送对话问题" :disabled="generating || !draft.trim()">↑</button>
          </form>
          <p>本地 Mock 流式输出 · 未连接模型 · 不保存对话</p>
        </div>
      </div>
      <aside v-if="pipelineOpen" class="assistant-pipeline">
        <div class="pipeline-title">
          <h2>检索管线</h2>
          <button aria-label="收起检索管线" @click="pipelineOpen = false">收起 ›</button>
        </div>
        <p class="assistant-status">模拟资料匹配过程，不是实际模型指标</p>
        <label for="assistant-turn">问题</label
        ><select id="assistant-turn" v-model="selectedTurn">
          <option :value="null">最新问题</option>
          <option v-for="turn in turns" :key="turn.id" :value="turn.id">{{ turn.question }}</option>
        </select>
        <p v-if="!current" class="assistant-status">提问后，这里会显示该轮使用的公开资料。</p>
        <template v-else
          ><section>
            <small>01</small>
            <h3>理解问题</h3>
            <p>{{ current.question }}</p>
            <p v-if="current.quote">附带选中原文</p>
          </section>
          <section>
            <small>02</small>
            <h3>匹配公开资料</h3>
            <a
              v-for="source in current.sources"
              :key="source.url"
              :href="source.url"
              class="assistant-source"
              @click="close"
              >{{ source.title }} ↗</a
            >
            <p v-if="!current.sources.length">没有匹配到足够资料</p>
          </section>
          <section>
            <small>03</small>
            <h3>组织回答</h3>
            <p>
              {{
                current.status === 'generating'
                  ? '正在流式输出…'
                  : current.status === 'complete'
                    ? '✓ 完成'
                    : current.status === 'stopped'
                      ? '已停止'
                      : '未完成'
              }}
            </p>
          </section></template
        >
      </aside>
      <div
        v-if="tools"
        class="selection-tools"
        role="toolbar"
        aria-label="选区 AI 操作"
        :style="{ left: tools.x + 'px', top: tools.y + 'px' }"
        @pointerdown.prevent
      >
        <button
          v-for="action in ['解释这段', '展开讲讲', '就这段提问']"
          :key="action"
          @click="selectAction(action)"
        >
          {{ action }}
        </button>
      </div>
    </dialog>
  </Teleport>
</template>

<style scoped>
.home-ask {
  display: grid;
  grid-template-columns: 1fr 1.15fr;
  gap: 32px;
  padding: 28px 30px;
  border: 1px solid var(--site-line);
  border-radius: 14px;
  background: var(--site-surface);
  margin-bottom: 34px;
}
.home-ask h2 {
  font-size: 25px;
  margin: 5px 0 7px;
  font-weight: 650;
}
.assistant-kicker {
  font-size: 10px;
  letter-spacing: 1px;
  color: var(--site-accent);
}
.assistant-form {
  display: flex;
  gap: 8px;
  border: 1px solid var(--site-line);
  padding: 6px;
  border-radius: 9px;
  background: var(--site-bg);
}
.home-ask .assistant-form {
  position: relative;
  isolation: isolate;
  overflow: hidden;
  transition:
    border-color 180ms ease,
    box-shadow 180ms ease;
}
.home-ask .assistant-form::after {
  content: '';
  position: absolute;
  inset: 0;
  pointer-events: none;
  opacity: 0;
  background: linear-gradient(
    105deg,
    transparent 25%,
    color-mix(in srgb, var(--site-accent) 24%, transparent) 50%,
    transparent 75%
  );
  transform: translateX(-110%);
}
.is-inviting .assistant-form {
  animation: invitation-glow 2.6s ease-in-out 2;
}
.is-inviting .assistant-form::after {
  animation: invitation-sheen 2.6s ease-in-out 2;
}
.assistant-form:focus-within {
  outline: 2px solid var(--site-accent);
}
.assistant-form input {
  flex: 1;
  min-width: 0;
  background: none;
  border: 0;
  padding: 8px;
  font: inherit;
  font-size: 13px;
  outline: none;
  color: var(--site-text);
}
.assistant-form button {
  background: var(--site-accent);
  color: var(--site-inverted);
  border-radius: 6px;
  padding: 5px 13px;
}
.assistant-form button:disabled {
  opacity: 0.45;
}
.assistant-suggestions {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  margin-top: 11px;
}
.assistant-suggestions button {
  font-size: 11px;
  border: 1px solid var(--site-line);
  border-radius: 20px;
  padding: 5px 11px;
  color: var(--site-muted);
}
.assistant-launch {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 35;
  display: flex;
  align-items: center;
  gap: 9px;
  background: var(--site-text);
  color: var(--site-bg);
  padding: 12px 20px;
  border-radius: 30px;
  box-shadow: var(--site-shadow);
}
.assistant-launch {
  transition:
    transform 180ms ease,
    box-shadow 180ms ease;
}
.assistant-launch:hover,
.assistant-launch:focus-visible {
  transform: translateY(-3px);
  box-shadow: 0 7px 24px color-mix(in srgb, var(--site-accent) 22%, transparent);
}
.assistant-suggestions button {
  transition:
    color 160ms ease,
    background-color 160ms ease,
    border-color 160ms ease;
}
.assistant-suggestions button:hover,
.assistant-suggestions button:focus-visible {
  color: var(--site-text);
  border-color: var(--site-accent);
  background: var(--site-bg-muted);
}
@keyframes invitation-glow {
  0%,
  100% {
    border-color: var(--site-line);
    box-shadow: 0 0 0 0 transparent;
  }
  35%,
  60% {
    border-color: var(--site-accent);
    box-shadow:
      0 0 0 4px color-mix(in srgb, var(--site-accent) 10%, transparent),
      0 0 22px color-mix(in srgb, var(--site-accent) 14%, transparent);
  }
}
@keyframes invitation-sheen {
  0%,
  15% {
    opacity: 0;
    transform: translateX(-110%);
  }
  30% {
    opacity: 1;
  }
  70%,
  100% {
    opacity: 0;
    transform: translateX(110%);
  }
}
.assistant-drawer {
  position: fixed;
  inset: 0 0 0 auto;
  margin: 0;
  padding: 0;
  border: 0;
  border-left: 1px solid var(--site-line);
  height: 100dvh;
  max-height: 100dvh;
  max-width: 100%;
  background: var(--site-bg);
  color: var(--site-text);
  overflow: hidden;
}
.assistant-drawer[open] {
  display: flex;
  animation: drawer-in 0.23s ease-out;
}
.assistant-drawer::backdrop {
  background: #0006;
  backdrop-filter: blur(3px);
}
.assistant-chat {
  display: flex;
  flex-direction: column;
  min-height: 0;
  min-width: 0;
  flex: 1;
}
.assistant-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 22px;
  border-bottom: 1px solid var(--site-line);
}
.assistant-head small {
  display: block;
  font-size: 10px;
  color: var(--site-muted);
  margin-top: 4px;
}
.assistant-head-actions {
  display: flex;
  gap: 12px;
  align-items: center;
  font-size: 11px;
  white-space: nowrap;
}
.assistant-head-actions button:last-child {
  font-size: 22px;
}
.assistant-log {
  overflow: auto;
  flex: 1;
  min-height: 0;
  padding: 24px;
  overscroll-behavior: contain;
}
.assistant-welcome span {
  color: var(--site-accent);
  font-size: 28px;
}
.assistant-welcome h2 {
  font-size: 28px;
  margin: 12px 0;
}
.assistant-welcome p {
  font-size: 13px;
  color: var(--site-muted);
}
.assistant-turn {
  margin-top: 25px;
  font-size: 14px;
}
.assistant-question {
  padding: 12px 16px;
  margin-left: 30px;
  background: var(--site-bg-muted);
  border-radius: 12px;
}
.assistant-question blockquote {
  font-size: 12px;
  border-left: 2px solid var(--site-accent);
  padding-left: 10px;
  margin-bottom: 10px;
  max-height: 120px;
  overflow: auto;
}
.assistant-answer {
  padding-top: 22px;
}
.assistant-answer small {
  font-size: 10px;
  color: var(--site-accent);
}
.stream-content {
  white-space: pre-wrap;
  line-height: 1.9;
  margin: 12px 0;
}
.stream-caret {
  display: inline-block;
  width: 2px;
  height: 1em;
  margin-left: 3px;
  vertical-align: -0.1em;
  background: var(--site-accent);
  animation: caret-blink 1s steps(2) infinite;
}
.assistant-source {
  display: block;
  padding: 10px 12px;
  border: 1px solid var(--site-line);
  border-radius: 7px;
  margin-top: 9px;
  font-size: 12px;
  background: var(--site-surface);
}
.assistant-bottom {
  padding: 16px 24px 20px;
  border-top: 1px solid var(--site-line);
}
.assistant-bottom > p {
  font-size: 10px;
  color: var(--site-muted);
  text-align: center;
  margin-top: 8px;
}
.assistant-quote {
  display: flex;
  gap: 10px;
  border-left: 2px solid var(--site-accent);
  background: var(--site-bg-muted);
  padding: 8px 12px;
  margin-bottom: 10px;
  font-size: 12px;
}
.assistant-quote span {
  max-height: 80px;
  overflow: auto;
  flex: 1;
}
.stop-button,
.latest-button {
  font-size: 12px;
  color: var(--site-accent);
  padding: 6px 0;
}
.latest-button {
  align-self: center;
}
.assistant-resize {
  position: absolute;
  inset: 0 auto 0 0;
  width: 8px;
  cursor: ew-resize;
  touch-action: none;
  z-index: 5;
}
.assistant-resize::after {
  content: '';
  position: absolute;
  top: 45%;
  left: 2px;
  width: 3px;
  height: 45px;
  border-radius: 3px;
  background: var(--site-muted);
}
.assistant-pipeline {
  width: 300px;
  flex-shrink: 0;
  padding: 26px 22px;
  overflow: auto;
  background: var(--site-surface);
  border-left: 1px solid var(--site-line);
  overscroll-behavior: contain;
}
.pipeline-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
}
.pipeline-title h2 {
  font-size: 25px;
}
.pipeline-title button {
  font-size: 11px;
  color: var(--site-muted);
}
.assistant-status {
  font-size: 11px;
  color: var(--site-muted);
  margin: 14px 0;
}
.assistant-pipeline label {
  font-size: 11px;
}
.assistant-pipeline select {
  width: 100%;
  background: var(--site-bg);
  border: 1px solid var(--site-line);
  padding: 9px;
  font-size: 12px;
  border-radius: 6px;
  margin-top: 8px;
}
.assistant-pipeline section {
  border-bottom: 1px solid var(--site-line);
  padding: 22px 0;
}
.assistant-pipeline h3 {
  font-size: 14px;
  margin: 6px 0;
}
.assistant-pipeline section small,
.assistant-pipeline section p {
  font-size: 11px;
  color: var(--site-muted);
}
.selection-tools {
  position: fixed;
  z-index: 60;
  display: flex;
  padding: 4px;
  border: 1px solid var(--site-line);
  border-radius: 8px;
  background: var(--site-surface);
  color: var(--site-text);
  box-shadow: var(--site-shadow);
}
.selection-tools button {
  font-size: 12px;
  padding: 8px 10px;
}
.selection-tools button:hover {
  background: var(--site-bg-muted);
}
.narrow-drawer .assistant-pipeline {
  position: absolute;
  inset: 82px 0 130px auto;
  max-width: 90%;
  box-shadow: var(--site-shadow);
}
@keyframes drawer-in {
  from {
    opacity: 0;
    transform: translateX(25px);
  }
  to {
    opacity: 1;
    transform: translateX(0);
  }
}
@keyframes caret-blink {
  50% {
    opacity: 0;
  }
}
@media (max-width: 800px) {
  .home-ask {
    grid-template-columns: 1fr;
    gap: 18px;
    padding: 22px;
  }
  .assistant-pipeline {
    position: absolute;
    inset: 82px 0 130px auto;
    max-width: 90%;
    box-shadow: var(--site-shadow);
  }
}
@media (max-width: 600px) {
  .assistant-drawer {
    width: 100% !important;
  }
  .assistant-resize {
    display: none;
  }
  .assistant-head {
    padding: 17px;
  }
  .assistant-head-actions {
    gap: 9px;
  }
  .assistant-head strong {
    font-size: 14px;
  }
  .assistant-launch {
    right: 16px;
    bottom: 16px;
    padding: 10px 14px;
    font-size: 12px;
  }
  .assistant-log {
    padding: 20px;
  }
}
@media (prefers-reduced-motion: reduce) {
  *,
  *::before,
  *::after {
    animation: none !important;
    transition: none !important;
  }
}
</style>
