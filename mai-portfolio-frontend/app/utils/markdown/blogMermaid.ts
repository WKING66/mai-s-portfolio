export const MERMAID_LIMITS = {
  characters: 6000,
  edges: 120,
  nodes: 2500,
  svgCharacters: 300000,
  cacheEntries: 16,
} as const
export type MermaidPalette = {
  background: string
  surface: string
  text: string
  primary: string
  border: string
}

/** 不允许文档改变站点安全配置或加载外部图像；失败时组件显示完整原文。 */
export function validateMermaidSource(source: string) {
  if (source.length > MERMAID_LIMITS.characters) {
    throw new Error('图表超过安全渲染限额，请查看源码。')
  }
  if (/^\s*---|%%\s*\{\s*(?:init|config)\s*:|@\{[^}]*\bimg\s*:/im.test(source)) {
    throw new Error('图表配置指令或外部图片未开放，请查看源码。')
  }
  const diagramStyles = source.match(/(?:^|;)\s*(?:classDef|style)\b[^\n]*/gm) || []
  if (
    diagramStyles.some((style) =>
      /[{}\\@]|(?:url|expression)\s*\(|(?:animation|transition|filter|position|z-index|behavior)\s*:/i.test(
        style,
      ),
    )
  ) {
    throw new Error('图表包含不安全样式，请查看源码。')
  }
}

const svgCache = new Map<string, Promise<string>>()
let renderQueue: Promise<unknown> = Promise.resolve(),
  renderSequence = 0

function safeSvg(svg: string, rootId: string, purify: typeof import('dompurify').default) {
  if (svg.length > MERMAID_LIMITS.svgCharacters) {
    throw new Error('图表渲染结果过大，请查看源码。')
  }
  // 1. DOMPurify 清理生成物，再限制节点数和 SVG 根节点。
  const sanitizedSvg = purify.sanitize(svg, {
    USE_PROFILES: { svg: true, svgFilters: true },
    FORBID_TAGS: ['foreignObject', 'a', 'image', 'use', 'script', 'animate', 'set'],
    FORBID_ATTR: ['href', 'xlink:href'],
  })
  const svgDocument = new DOMParser().parseFromString(sanitizedSvg, 'image/svg+xml')
  if (
    svgDocument.querySelector('parsererror') ||
    svgDocument.documentElement.tagName !== 'svg' ||
    svgDocument.querySelectorAll('*').length > MERMAID_LIMITS.nodes
  ) {
    throw new Error('图表无法安全展示，请查看源码。')
  }
  // CSSOM 仅解析，不挂载样式表；生成物的每个选择器必须限制于本图表。
  for (const style of svgDocument.querySelectorAll('style')) {
    const sheet = new CSSStyleSheet()
    sheet.replaceSync(style.textContent || '')
    const animations = new Map<string, string>()
    for (const rule of sheet.cssRules) {
      if (rule instanceof CSSKeyframesRule) {
        // 官方默认两个描边关键帧；只接受数值描边偏移，并隔离全局动画名称。
        if (
          !['dash', 'edge-animation-frame'].includes(rule.name) ||
          [...rule.cssRules].some(
            (frame) =>
              !(frame instanceof CSSKeyframeRule) ||
              [...frame.style].some(
                (property) =>
                  property !== 'stroke-dashoffset' ||
                  !/^-?[\d.]+(?:px|%)?$/.test(frame.style.getPropertyValue(property)),
              ),
          )
        ) {
          throw new Error('图表包含不安全动画，请查看源码。')
        }
        const name = rootId + '-animation-' + animations.size
        animations.set(rule.name, name)
        rule.name = name
      }
    }
    for (const rule of sheet.cssRules) {
      if (rule instanceof CSSKeyframesRule) {
        continue
      }
      if (
        !(rule instanceof CSSStyleRule) ||
        !rule.selectorText
          .split(',')
          .every(
            (selector) =>
              selector.trim() === '#' + rootId || selector.trim().startsWith('#' + rootId + ' '),
          )
      ) {
        throw new Error('图表样式超出组件范围，请查看源码。')
      }
      if (
        /[\\]|@import|expression\s*\(/i.test(rule.style.cssText) ||
        [...rule.style.cssText.matchAll(/url\s*\(([^)]*)\)/gi)].some(
          (match) => !/^['"]?#[\w:-]+['"]?$/.test(match[1]!.trim()),
        )
      ) {
        throw new Error('图表包含外部样式引用，请查看源码。')
      }
      if (rule.style.animationName) {
        rule.style.animationName = rule.style.animationName
          .split(',')
          .map((name) => animations.get(name.trim()) || name.trim())
          .join(',')
      }
    }
    style.textContent = [...sheet.cssRules].map((rule) => rule.cssText).join('\n')
  }
  // 2. 属性引用仅允许当前图内部目标；禁止事件与外部资源引用。
  for (const element of svgDocument.querySelectorAll('*')) {
    for (const attribute of element.attributes) {
      if (/^on/i.test(attribute.name)) {
        throw new Error('图表包含交互属性，请查看源码。')
      }
      for (const match of attribute.value.matchAll(/url\s*\(([^)]*)\)/gi)) {
        if (!/^['"]?#[\w:-]+['"]?$/.test(match[1]!.trim())) {
          throw new Error('图表包含外部引用，请查看源码。')
        }
      }
    }
  }
  return new XMLSerializer().serializeToString(svgDocument.documentElement)
}

/** 缓存安全 SVG 模板；每个实例重新命名 ID/内部引用，重复图表不会冲突。 */
export function cloneMermaidSvg(svg: string, prefix: string) {
  const svgDocument = new DOMParser().parseFromString(svg, 'image/svg+xml')
  const width = Number(svgDocument.documentElement.getAttribute('viewBox')?.split(/[ ,]+/)[2])
  if (Number.isFinite(width) && width > 0) {
    ;(svgDocument.documentElement as unknown as SVGElement).style.width = width + 'px'
  }
  const svgIds = new Map<string, string>()
  const rootId = svgDocument.documentElement.id
  for (const element of svgDocument.querySelectorAll('[id]')) {
    const id = element.id,
      clonedId = prefix + '-' + svgIds.size
    svgIds.set(id, clonedId)
    element.id = clonedId
  }
  const rewriteReferences = (value: string) =>
    value.replace(/#([\w:-]+)/g, (match, id) => (svgIds.has(id) ? '#' + svgIds.get(id) : match))
  for (const element of svgDocument.querySelectorAll('*')) {
    if (element.tagName === 'style') {
      element.textContent = rewriteReferences(element.textContent || '').replaceAll(
        rootId + '-animation-',
        svgIds.get(rootId) + '-animation-',
      )
    }
    for (const attribute of element.attributes) {
      if (attribute.name !== 'id') {
        element.setAttribute(
          attribute.name,
          attribute.name === 'aria-labelledby' || attribute.name === 'aria-describedby'
            ? attribute.value
                .split(' ')
                .map((id) => svgIds.get(id) || id)
                .join(' ')
            : rewriteReferences(attribute.value),
        )
      }
    }
  }
  return new XMLSerializer().serializeToString(svgDocument.documentElement)
}

export async function renderBlogMermaid(
  source: string,
  palette: MermaidPalette,
  instanceId: string,
) {
  validateMermaidSource(source)
  // 3. 源码与配色共同决定缓存；串行渲染避免 Mermaid 全局配置相互覆盖。
  const key = source + '\0' + JSON.stringify(palette)
  let result = svgCache.get(key)
  if (!result) {
    result = renderQueue.then(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0))
      const [{ default: mermaid }, { default: purify }] = await Promise.all([
        import('mermaid'),
        import('dompurify'),
      ])
      mermaid.initialize({
        startOnLoad: false,
        securityLevel: 'strict',
        htmlLabels: false,
        suppressErrorRendering: true,
        maxTextSize: MERMAID_LIMITS.characters,
        maxEdges: MERMAID_LIMITS.edges,
        secure: [
          'secure',
          'securityLevel',
          'startOnLoad',
          'maxTextSize',
          'maxEdges',
          'suppressErrorRendering',
          'htmlLabels',
          'themeCSS',
          'dompurifyConfig',
        ],
        theme: 'base',
        look: 'classic',
        fontFamily: 'sans-serif',
        flowchart: { htmlLabels: false },
        themeVariables: {
          background: palette.background,
          primaryColor: palette.surface,
          primaryTextColor: palette.text,
          primaryBorderColor: palette.border,
          secondaryColor: palette.background,
          tertiaryColor: palette.surface,
          textColor: palette.text,
          lineColor: palette.primary,
          mainBkg: palette.surface,
        },
      })
      const id = 'blog-mermaid-' + ++renderSequence
      const { svg } = await mermaid.render(id, source)
      // 不执行 Mermaid 返回的 bindFunctions；严格模式之外再过滤 SVG 和作用域。
      return safeSvg(svg, id, purify)
    })
    renderQueue = result.catch(() => {})
    svgCache.set(key, result)
    while (svgCache.size > MERMAID_LIMITS.cacheEntries) {
      svgCache.delete(svgCache.keys().next().value!)
    }
    // 错误结果同样受缓存容量限制，避免主题来回切换反复解析无效图表。
  }
  return cloneMermaidSvg(await result, instanceId)
}
