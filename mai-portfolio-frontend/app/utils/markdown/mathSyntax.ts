/** 编辑导入与公开阅读共用分隔符；TeX 是否能渲染不改变它的资源隔离边界。 */
export function matchMathSource(source: string, block: boolean) {
  const match = source.match(
    block
      ? /^(?:\$\$[\s\S]+?\$\$|\\\[[\s\S]+?\\\])(?:\n|$)/
      : // 转义和普通字符分支互斥，未闭合的长反斜线串不会指数回溯。
        /^(?:\$(?!\$)(?:\\.|[^\\$\n])+?\$(?![$\d])|\\\([\s\S]+?\\\))/,
  )
  if (!match) {
    return
  }
  const raw = match[0]
  const value = raw.trimEnd()
  const delimiter = value.startsWith('$') && !block ? 1 : 2
  return { raw, content: value.slice(delimiter, -delimiter) }
}
