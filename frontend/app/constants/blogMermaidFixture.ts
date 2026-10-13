/** 隔离的阅读器回归文档；不导入、不保存到真实博客。 */
export const BLOG_MERMAID_FIXTURE = `# Mermaid 阅读样例

## 流程图

\`\`\`mermaid
flowchart TD
    A[上传文档] --> B[Markdown解析]
    B --> C{解析成功?}
    C -->|是| D[保存文章]
    C -->|否| E[返回错误]
\`\`\`

## 时序图

\`\`\`mermaid
sequenceDiagram
    participant User
    participant Nuxt
    participant API
    User->>Nuxt: 打开文章
    Nuxt->>API: 请求Markdown
    API-->>Nuxt: 返回正文
    Nuxt-->>User: 渲染文章
\`\`\`

## 类图

\`\`\`mermaid
classDiagram
    class Blog {
      +String title
      +String markdown
      +publish()
    }
    Blog --> Document
\`\`\`

## 状态图

\`\`\`mermaid
stateDiagram-v2
    [*] --> Draft
    Draft --> Published: publish
    Published --> Draft: unpublish
\`\`\`

## ER 图

\`\`\`mermaid
erDiagram
    BLOG ||--|| DOCUMENT : contains
    DOCUMENT ||--o{ RESOURCE : references
\`\`\`

## 错误回退

\`\`\`mermaid
flowchart TD
    A --> (((
\`\`\`

## 普通代码

\`\`\`typescript [Mermaid.ts]
const source = "flowchart TD; A --> B"
\`\`\`
`
