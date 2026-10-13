export const FEEDBACK_KINDS = {
  success: { title: '操作成功', icon: 'i-lucide-circle-check' },
  error: { title: '操作失败', icon: 'i-lucide-circle-alert' },
  info: { title: '提示', icon: 'i-lucide-info' },
} as const

export type FeedbackKind = keyof typeof FEEDBACK_KINDS
