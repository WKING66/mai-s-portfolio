export const ASSISTANT_QUESTION_LIMIT = 300
export const ASSISTANT_QUOTE_LIMIT = 200

export function prepareAssistantQuote(selection: string) {
  const text = selection.trim()
  if (text.length <= ASSISTANT_QUOTE_LIMIT) {
    return { text, truncated: false }
  }
  // Java String.length and browser maxlength both count UTF-16 code units.
  // Keep a surrogate pair intact when the selection crosses the limit.
  const prefix = text.slice(0, ASSISTANT_QUOTE_LIMIT)
  return { text: prefix.replace(/[\uD800-\uDBFF]$/, ''), truncated: true }
}

export function formatAssistantQuestion(question: string, quote = '') {
  const trimmed = question.trim()
  return quote && trimmed ? `参考以下摘录：\n${quote}\n\n${trimmed}` : trimmed
}
