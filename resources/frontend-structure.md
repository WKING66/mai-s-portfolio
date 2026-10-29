# 前端代码结构

应用源码位于 `mai-portfolio-frontend/`。目录按职责归类，不为不同页面重复维护 Markdown 解析、代码高亮或资源规则。

```text
app/
  components/    # account、admin、auth、blog、editor、profile、project、site、ui
  composables/   # 页面与编辑会话状态
  utils/
    markdown/    # 共享解析、公式、Mermaid、code 高亮
    blog/        # 章节、资源身份、slug
    editor/
      code/        # CodeMirror、格式化、代码选区
      interaction/ # 多块选择、拖拽、章节、表格、剪贴板
      markdown/    # 画布导入导出、语义校验及 Worker 客户端
      session/     # 初始化、资源、保存契约与博客持久化
      math/        # 公式预览调度及布局
      diagnostics/ # 手动开启的性能采样
    account/ assets/ project/ site/
  workers/       # Markdown、代码高亮、代码格式化、编辑校验入口
tests/
  unit/          # 按领域组织的功能测试
  fixtures/      # 固定输入
  support/       # 仅测试使用的上传、保存与校验适配
```

## 编辑会话的职责

- `components/blog/BlogEditor.vue` 持有实际文章会话，连接 API、资源上传和正文／属性保存。
- `components/editor/ImmersiveWorkbench.client.vue` 组织画布、目录和设置交互。属性保存与正文保存保持独立。
- `composables/useCanvasDocument.ts` 管理画布文档、初始化、后台校验及保存准备；必须注入真实持久化入口。
- `utils/editor/session/types.ts` 统一保存快照、文章属性和持久化接口，避免类型分散或互相引用实现。
- `utils/editor/markdown/validation.ts` 是 Worker 与测试共用的画布校验入口，复用当前导入、导出及完整语义校验。已移除旧源码双栏协议及其独立解析链。

公开阅读页与编辑器共享 Markdown 和阅读内容块能力；编辑器仍使用自己的 ProseMirror 文档和节点视图。多块选区、拖拽、表格多选、撤销和公式预览调度保留现有实现。历史单行／单列表格辅助入口不再作为另一套交互保留。

演示路由、模拟保存与模拟上传不进入正式运行路径。固定测试数据和测试替身放在 `tests/`，手动性能采样保留在编辑工具栏，默认不开启。

## 开发与验证

使用 Node.js 24 与 pnpm。在应用目录执行：

```sh
pnpm dev
pnpm typecheck
pnpm test -- tests/unit/editor/canvasDocument.test.ts --maxWorkers=2
pnpm build
```

`pnpm typecheck` 同时检查应用和独立测试目录。测试移出 `app/`，避免被 Nuxt 当成运行时代码；`tsconfig.tests.json` 保留测试的类型检查。

按改动范围选择验证；类型、单元测试和构建不能替代真实浏览器的输入、选择与拖拽验收。
