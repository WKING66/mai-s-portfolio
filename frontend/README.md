# 前端代码检查

使用 Node.js 24 和 npm。`npm ci` 会执行 `nuxt prepare`，生成 Nuxt 类型和 ESLint 配置；
已有依赖时也可以运行 `npx nuxt prepare`。生成目录不提交。

```sh
npm run lint
npm run lint -- app/components/blog/BlogTocTree.vue
npm run lint:fix -- nuxt.config.ts
npm run format -- nuxt.config.ts package.json
npm run format -- .
npm run format:check
```

`lint` 默认检查整个前端，也接受文件路径做定向检查。`format` 必须指定目标路径：
日常传入本批文件，全部整改完成后使用 `.`。`format:check` 检查所有前端自有源码。

ESLint 使用 Nuxt 的项目感知 Flat Config，检查 JS、TypeScript 和 Vue 的代码质量，
强制花括号、多行代码块、每行至多一个语句，并禁止嵌套三元表达式。
Prettier 统一 100 字符目标行宽、两空格缩进、单引号、无分号、UTF-8/LF 编辑约定。
`eslint-config-prettier` 关闭冲突的排版规则，仅重新启用与 Prettier 一致的多行块要求。

不要通过忽略业务目录或关闭规则绕过存量问题。模块按批整改，尚未整改的全量检查错误
应保留并报告；全量检查失败不能写成通过。注释解释真实业务步骤、状态边界和性能取舍，
不为简单映射或 Getter 机械添加步骤。格式整改不改变 API 字段、权限、路由和业务流程。
