// 全站组件默认样式统一交给 Nuxt UI；代码块局部主题仍由块内语义变量覆盖。
export default defineAppConfig({
  ui: {
    colors: { primary: 'teal', neutral: 'slate' },
    button: { slots: { base: 'cursor-pointer disabled:cursor-not-allowed rounded-lg' } },
    input: { slots: { base: 'rounded-lg' } },
    textarea: { slots: { base: 'rounded-lg' } },
    select: {
      slots: {
        base: 'rounded-lg max-w-full',
        content: 'rounded-lg max-w-[calc(100vw-2*var(--site-gutter))]',
      },
    },
    formField: { slots: { root: 'min-w-0 max-w-full' } },
    card: {
      slots: {
        root: 'min-w-0 rounded-xl shadow-none',
        header: 'p-5 sm:p-6',
        body: 'p-5 sm:p-6',
        footer: 'p-5 sm:p-6',
      },
    },
    modal: { slots: { content: 'rounded-xl shadow-lg' } },
    dropdownMenu: {
      slots: { content: 'rounded-lg shadow-sm max-w-[calc(100vw-2*var(--site-gutter))]' },
    },
  },
})
