/** 仅供样板页使用；不进入站点主题偏好、业务表单或持久化状态。 */
export const SHOWCASE_PORTAL = '#design-showcase-portals'
export const SHOWCASE_SPACING = [4, 8, 12, 16, 24, 32, 48, 64] as const
export const SHOWCASE_COLORS = ['primary', 'neutral', 'success', 'info', 'warning', 'error'] as const
export const SHOWCASE_PALETTES = {
  light: { background: '#F5F7F6', surface: '#FFFFFF', text: '#1F2D2B', primary: '#36776D' },
  dark: { background: '#10191C', surface: '#182326', text: '#E5EEEB', primary: '#82B8AD' },
} as const
export const SHOWCASE_NAVIGATION = [
  { label: '设计规范', to: '#tokens' },
  { label: '组件', to: '#components' },
  { label: '阅读', to: '#reading' },
  { label: '写作', to: '#writing' },
]
export type ShowcaseTheme = keyof typeof SHOWCASE_PALETTES
export const SHOWCASE_ARTICLE = {
  title: '让内容成为界面的主角',
  lead: '一个克制的视觉系统，让读者专注于文章，让创作者专注于表达。Less decoration, more clarity.',
  paragraph: '技术文章需要清晰的层次，也需要足够安静的阅读空间。中英文混排、链接、列表与代码应该自然衔接，而不是彼此争抢注意力。我们用适度的留白组织内容，用低饱和青绿标记重点，用冷灰建立稳定的视觉秩序。长段落应当保持舒适的行高，在手机上自然换行，在桌面上限制阅读宽度。',
  headings: ['一级标题 · Article title', '二级标题 · Section', '三级标题 · Topic', '四级标题 · Detail', '五级标题 · Note', '六级标题 · Caption'],
  quote: '好的排版，是让复杂的信息更容易被理解。',
  list: ['明确的标题层次', '稳定的段落节奏', '可访问的交互反馈'],
  columns: ['层次', '用途', '阅读原则'],
  rows: [['标题', '建立结构', '清晰而克制'], ['正文', '解释概念', '舒适的行高'], ['代码', '给出证据', '保留空白与长行']],
  codes: [
    { language: 'Java', filename: 'ArticleService.java', code: 'public record Article(String title, String markdown) {}\n\npublic Article readPublished(long id) {\n    return repository.findPublished(id);\n}' },
    { language: 'Python', filename: 'reading.py', code: 'def reading_minutes(markdown: str) -> int:\n    words = len(markdown.split())\n    return max(1, round(words / 200))\n\nprint("Readable systems begin with clear boundaries.")' },
    { language: 'TypeScript', filename: 'article.ts', code: 'type Article = { title: string; content: string }\n\nconst article: Article = { title: "Keep the source", content: "Markdown is the exchange format. Rendering belongs to the frontend, and this intentionally long line checks horizontal scrolling without stretching the page." }\n\nconsole.log(article.content)' },
  ],
} as const
// 文章正文由同一组静态 Markdown 内容生成，统一交给 Comark + Prose 渲染。
export const SHOWCASE_MARKDOWN = [
  '# ' + SHOWCASE_ARTICLE.title,
  SHOWCASE_ARTICLE.lead, SHOWCASE_ARTICLE.paragraph,
  '## 阅读节奏',
  SHOWCASE_ARTICLE.paragraph,
  '> ' + SHOWCASE_ARTICLE.quote,
  '### 结构与层次',
  SHOWCASE_ARTICLE.list.map(text => '- ' + text).join('\n'),
  '1. 先确定视觉规范\n2. 再复用到实际页面',
  '#### 四级标题 · Detail\n\n##### 五级标题 · Note\n\n###### 六级标题 · Caption',
  SHOWCASE_ARTICLE.paragraph,
  '## 代码展示',
  ...SHOWCASE_ARTICLE.codes.map(({ language, filename, code }) => '```' + language.toLowerCase() + ' [' + filename + ']\n' + code + '\n```'),
  '```javascript\nconst reading = { clear: true, theme: "follow" };\n```',
  '```json\n{ "format": "Markdown", "published": true }\n```',
  '```sql\nSELECT title, content FROM blog WHERE published = true;\n```',
  '```bash\nnpm run build\n```',
  '```\n没有语言标记，也可以保留原始 <代码> 与空白。\n```',
  '```unknown-language\n<safe-fallback>Unsupported languages stay plain text.</safe-fallback>\n```',
  '## 表格与图片',
  '| ' + SHOWCASE_ARTICLE.columns.join(' | ') + ' |\n| --- | --- | --- |\n' + SHOWCASE_ARTICLE.rows.map(row => '| ' + row.join(' | ') + ' |').join('\n'),
  '![青绿与冷灰的几何构图](/images/design-showcase.svg)',
  '[Nuxt UI](https://ui.nuxt.com) · `Markdown` · **重点** · *补充说明*',
  '---',
].join('\n\n')
export const SHOWCASE_EDITOR_MARKDOWN = '# 写下值得分享的想法\n\n选中这段文字，试试 **加粗**、*斜体*、~~删除线~~，或使用上方工具栏。\n\n## 一个清晰的起点\n\n- 保留 Markdown 源码\n  - 让内容往返保持完整\n- 让写作保持专注\n\n> 清晰的表达始于清晰的结构。\n\n| 内容 | 格式 |\n| --- | --- |\n| 博客 | Markdown |\n| 代码 | 带语言标记 |\n\n[阅读 Nuxt UI 文档](https://ui.nuxt.com)\n\n```typescript\nconst message = "Hello, Markdown"\n```\n'
export const SHOWCASE_THEME_UI = {
  button: { base: 'rounded-lg' },
  card: { root: 'rounded-xl shadow-none', header: 'p-5 sm:p-6', body: 'p-5 sm:p-6', footer: 'p-5 sm:p-6' },
  input: { base: 'rounded-lg' },
  textarea: { base: 'rounded-lg' },
  select: { base: 'rounded-lg' },
  editor: { base: 'showcase-editor-body p-5 sm:p-6 min-h-80' },
  editorToolbar: { root: 'flex-wrap gap-1 border-b border-default p-2' },
  prose: { pre: { root: 'rounded-xl', base: 'font-mono text-sm leading-7', copy: 'opacity-100 lg:opacity-100' } },
}
