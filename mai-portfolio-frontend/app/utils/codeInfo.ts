/** 项目代码围栏约定：兼容既有 [filename] 与 title="..."，阅读与编辑共用。 */
export function parseCodeInfo(info: string) {
  const title = info.match(/(?:^|\s)title=("(?:\\.|[^"\\])*")/)
  let filename = info.match(/\[([^\]]+)\]/)?.[1] || ''
  if (title) {
    try {
      filename = JSON.parse(title[1]!)
    } catch {
      /* 无效 title 原样留在 info string 中，不猜测文件名。 */
    }
  }
  return { language: info.split(/\s/)[0] || '', filename }
}

export function formatCodeInfo(language: string, filename: string) {
  const cleanLanguage = language.replace(/[\s[\]]/g, '')
  const cleanFilename = filename.replace(/[\r\n]/g, '')
  return cleanLanguage + (cleanFilename ? ' title=' + JSON.stringify(cleanFilename) : '')
}
