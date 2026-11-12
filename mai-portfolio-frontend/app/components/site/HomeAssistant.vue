<script setup lang="ts">
import { useSiteAssistant } from '../../composables/useSiteAssistant'

defineProps<{ name: string }>()
const {
  enabled,
  turns,
  generating,
  latest,
  error,
  recoverable,
  canStop,
  histories,
  nextCursor,
  loggedIn,
  hasAnonymousHistory,
  send,
  stop,
  clear,
  resume,
  open: openConversation,
  loadHistory,
  importAnonymous,
} = useSiteAssistant()
const showHistory = ref(false)
const actionError = ref('')
async function manage(action: () => Promise<unknown>) {
  actionError.value = ''
  try {
    await action()
  } catch (failure) {
    actionError.value = failure instanceof Error ? failure.message : '会话操作失败。'
  }
}
const dialog = useTemplateRef<HTMLDialogElement>('drawer')
const log = useTemplateRef<HTMLElement>('log')
const input = useTemplateRef<HTMLInputElement>('input')
const draft = ref('')
const homeQuestion = ref('')
const inviting = ref(false)

function dismissInvitation() {
  inviting.value = false
}
const quote = ref('')
const selectedText = ref('')
const tools = ref<{ x: number; y: number } | null>(null)
const drawerWidth = ref(940)
const follow = ref(true)
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
  await send(question, contextQuote)
}
watch(
  () => latest.value?.text,
  async () => {
    await nextTick()
    if (follow.value) {
      jumpToLatest()
    }
  },
)
function clearConversation() {
  void manage(clear)
  quote.value = ''
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
  // 浮动入口短暂邀请一次，交互后停止；不循环提醒。
  inviting.value = !matchMedia('(prefers-reduced-motion: reduce)').matches
  document.addEventListener('pointerup', onPointerUp)
  document.addEventListener('scroll', hideTools, true)
  window.addEventListener('resize', onResize)
})
onBeforeUnmount(() => {
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
    v-if="enabled"
    class="home-ask"
    aria-labelledby="home-ask-title"
    @focusin="dismissInvitation"
    @pointerdown="dismissInvitation"
  >
    <div>
      <p class="assistant-kicker">● AMAI / AI GUIDE</p>
      <h2 id="home-ask-title">从一个问题开始，认识我。</h2>
      <p class="text-muted text-sm">关于我的项目、笔记和学习路线，问问站内向导。</p>
    </div>
    <div>
      <form class="assistant-form" @submit.prevent="submit(homeQuestion)">
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
  <button
    v-if="enabled"
    class="assistant-launch"
    :class="{ 'is-inviting': inviting }"
    @click="open"
    @animationend.self="$event.animationName.startsWith('launch-welcome') && dismissInvitation()"
  >
    <span v-if="inviting" class="assistant-launch-hint" aria-hidden="true"
      >想了解我？从这里问问</span
    >
    <span class="assistant-launch-icon" aria-hidden="true"><UIcon name="i-lucide-sparkles" /></span>
    <span>问问 AI</span><span aria-hidden="true">↗</span>
  </button>
  <Teleport v-if="enabled" to="body">
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
      :class="{ 'narrow-drawer': drawerWidth < 800 }"
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
            <strong id="assistant-title">{{ name }}的 AI 向导</strong><small>站长数字人</small>
          </div>
          <div class="assistant-head-actions">
            <button
              v-if="loggedIn"
              :aria-expanded="showHistory"
              @click="
                showHistory = !showHistory
                if (showHistory) manage(() => loadHistory())
              "
            >
              历史
            </button>
            <button v-if="loggedIn" :disabled="generating" @click="clearConversation">新建</button
            ><button aria-label="关闭 AI 向导" @click="close">×</button>
          </div>
        </header>
        <div v-if="loggedIn && showHistory" class="assistant-history">
          <button v-if="hasAnonymousHistory" @click="manage(importAnonymous)">导入匿名问答</button>
          <button
            v-for="item in histories"
            :key="item.conversationId"
            :disabled="generating"
            @click="manage(() => openConversation(item.conversationId))"
          >
            {{ new Date(item.updatedAt).toLocaleString() }}
          </button>
          <button v-if="nextCursor" @click="manage(() => loadHistory(true))">加载更多</button>
        </div>
        <div ref="log" class="assistant-log" role="log" aria-label="AI 对话" @scroll="trackScroll">
          <div class="assistant-welcome">
            <span>✳</span>
            <h2>你好，想了解什么？</h2>
            <p>你可以继续追问。资料不足时，回答会说明限制。</p>
          </div>
          <article v-for="turn in turns" :key="turn.requestId" class="assistant-turn">
            <div class="assistant-question">
              {{ turn.question }}
            </div>
            <div class="assistant-answer">
              <small>✳ AI 向导</small>
              <p class="stream-content">
                {{ turn.text
                }}<span
                  v-if="turn.status === 'running' || turn.status === 'accepted'"
                  class="stream-caret"
                  aria-hidden="true"
                />
              </p>
              <p v-if="turn.status === 'interrupted'" class="assistant-status">
                已停止生成，已输出内容保留。
              </p>
              <p v-if="turn.status === 'pending'" class="assistant-status">正在发送或确认受理。</p>
              <p v-if="turn.status === 'failed'" class="assistant-status">模型服务暂不可用。</p>
            </div>
          </article>
        </div>
        <button v-if="!follow" class="latest-button" @click="jumpToLatest">回到最新回复 ↓</button>
        <div class="assistant-bottom">
          <div v-if="quote" class="assistant-quote">
            <span>{{ quote }}</span
            ><button aria-label="移除引用" @click="quote = ''">×</button>
          </div>
          <p v-if="error || actionError" role="alert" class="assistant-status">
            {{ error || actionError }}
          </p>
          <button v-if="canStop" class="stop-button" @click="stop">■ 停止生成</button>
          <button v-if="recoverable && !generating" type="button" @click="resume">
            继续读取本轮
          </button>
          <form class="assistant-form" @submit.prevent="submit()">
            <input
              ref="input"
              v-model="draft"
              aria-label="继续提问"
              :placeholder="quote ? '关于所选内容，你想了解什么？' : '问问项目、文章或学习路线…'"
              maxlength="300"
              required
            /><button
              aria-label="发送对话问题"
              :disabled="generating || recoverable || !draft.trim()"
            >
              ↑
            </button>
          </form>
          <p>{{ loggedIn ? '登录会话可在历史中恢复' : '匿名模式保留当前会话' }}</p>
        </div>
      </div>
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
  gap: 10px;
  background: var(--site-text);
  color: var(--site-bg);
  padding: 11px 18px 11px 11px;
  border: 1px solid color-mix(in srgb, var(--site-accent) 40%, var(--site-text));
  border-radius: 30px;
  box-shadow: var(--site-shadow);
  transition:
    transform 180ms ease,
    box-shadow 180ms ease;
}
.assistant-launch::after {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: inherit;
  pointer-events: none;
  opacity: 0;
  background: linear-gradient(110deg, transparent 15%, #ffffff30 48%, transparent 80%);
  transform: scaleX(0.3);
}
.assistant-launch.is-inviting {
  animation: launch-welcome 8s ease both;
}
.assistant-launch.is-inviting::after {
  animation: launch-shimmer 2.6s ease-in-out 0.6s 2;
}
.assistant-launch-icon {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: var(--site-accent);
  color: var(--site-inverted);
}
.assistant-launch-hint {
  position: absolute;
  right: 0;
  bottom: calc(100% + 12px);
  padding: 10px 14px;
  white-space: nowrap;
  font-size: 12px;
  color: var(--site-text);
  background: var(--site-surface);
  border: 1px solid var(--site-line);
  border-radius: 12px 12px 4px 12px;
  box-shadow: var(--site-shadow);
  pointer-events: none;
  animation: launch-hint 8s ease both;
}
.assistant-launch:hover,
.assistant-launch:focus-visible {
  transform: translateY(-3px);
  box-shadow: 0 7px 24px color-mix(in srgb, var(--site-accent) 25%, transparent);
}
@keyframes launch-welcome {
  0% {
    opacity: 0;
    transform: translateY(18px) scale(0.96);
  }
  8%,
  35%,
  70%,
  100% {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
  22%,
  56% {
    opacity: 1;
    transform: translateY(-5px) scale(1.025);
  }
}
@keyframes launch-shimmer {
  0%,
  100% {
    opacity: 0;
    transform: scaleX(0.3);
  }
  40% {
    opacity: 1;
    transform: scaleX(1);
  }
}
@keyframes launch-hint {
  0%,
  10%,
  100% {
    opacity: 0;
    transform: translateY(6px);
  }
  20%,
  78% {
    opacity: 1;
    transform: translateY(0);
  }
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
.assistant-history {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 10px 24px;
}
.assistant-status {
  font-size: 11px;
  color: var(--site-muted);
  margin: 14px 0;
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
