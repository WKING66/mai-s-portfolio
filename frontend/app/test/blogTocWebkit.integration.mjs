import assert from 'node:assert/strict'
import {createRequire} from 'node:module'
import {mkdir,writeFile} from 'node:fs/promises'
import {resolve} from 'node:path'
import {execFileSync} from 'node:child_process'
const {webkit,chromium,devices}=createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE_PATH||'playwright')
const base=process.env.EXTERNAL_ACCEPTANCE_URL||'http://127.0.0.1:3000', engine=process.env.TOC_ENGINE||'webkit', label=process.env.TOC_LABEL||'candidate'
const output=resolve(process.env.TOC_OUTPUT||'../docs/testing/screenshots/ipad-reading-hotfix-20261012')
await mkdir(output,{recursive:true})
const articles=(await(await fetch(base+'/api/v1/blogs')).json()).data.items
const article=articles.find(item=>item.title.includes('数据结构与算法')); assert.ok(article)
const server=await (engine==='webkit'?webkit:chromium).launchServer({headless:true,...(engine==='edge'?{executablePath:process.env.BROWSER_EXECUTABLE,args:['--renderer-process-limit=1','--js-flags=--max-old-space-size=512']}: {})})
const timer=setTimeout(()=>{execFileSync('taskkill',['/PID',String(server.process().pid),'/T','/F'],{windowsHide:true,stdio:'ignore'});process.exit(124)},180000)
const browser=await(engine==='webkit'?webkit:chromium).connect(server.wsEndpoint()), rows=[],errors=[],warnings=[],pendingWarnings=[]
try {
 for(const theme of ['light','dark']) {
  const {defaultBrowserType,...options}=devices['iPad (gen 7)']
  const context=await browser.newContext({...options,reducedMotion:'reduce',colorScheme:theme})
  await context.addCookies([{name:'portfolio-theme',value:theme,url:base}])
  const page=await context.newPage();page.setDefaultTimeout(30000)
  page.on('pageerror',error=>errors.push(error.message));page.on('console',m=>{if(/hydration|mismatch/i.test(m.text())) { errors.push(m.text()); pendingWarnings.push(Promise.all(m.args().map(arg=>arg.evaluate(value=>value?.nodeType ? {html:value.outerHTML,text:value.textContent?.slice(0,180),parent:value.parentElement?.outerHTML.slice(0,1600)}:typeof value==='string'?value:typeof value).catch(()=>null))).then(args=>warnings.push({message:m.text(),args}))) }})
  await page.goto(base+'/blog/'+encodeURIComponent(article.slug));await page.locator('.blog-renderer[data-reading-ready="true"]').waitFor()
  if(!process.env.TOC_DIAGNOSTIC) assert.ok(await page.evaluate(()=>{
    const buttons=[...document.querySelectorAll('.blog-chapter-heading button')]
    const ids=buttons.map(button=>button.getAttribute('aria-controls'))
    return new Set(ids).size===ids.length&&buttons.every(button=>document.getElementById(button.getAttribute('aria-controls'))?.closest('.blog-chapter')===button.closest('.blog-chapter'))
  }),'水合后章节按钮关联的 body ID 必须唯一、存在且属于同一章节')
  for(const viewport of [{width:810,height:1080},{width:1080,height:810},{width:320,height:568}]) {
   await page.setViewportSize(viewport)
   await page.getByRole('button',{name:'打开文章目录',exact:true}).click();await page.getByRole('dialog').waitFor();await page.waitForTimeout(150)
   await page.getByRole('dialog').locator('.blog-toc-scroll').evaluate(el=>el.scrollTop=0)
   const geometry=await page.getByRole('dialog').evaluate(panel=>{
    const list=panel.querySelector('.blog-toc-scroll'), first=list.querySelector('a'), box=el=>{const r=el.getBoundingClientRect(),s=getComputedStyle(el);return {x:r.x,y:r.y,width:r.width,height:r.height,scrollHeight:el.scrollHeight,flex:s.flex,heightCSS:s.height}}
    const firstBox=first.getBoundingClientRect(), hit=document.elementFromPoint(firstBox.x+firstBox.width/2,firstBox.y+firstBox.height/2)
    return {panel:box(panel),body:box(panel.querySelector('[data-slot="body"]')),nav:box(panel.querySelector('nav')),list:box(list),first:box(first),firstHit:hit?.closest('a')===first,count:list.querySelectorAll('a').length,viewport:{width:innerWidth,height:innerHeight},overflow:document.documentElement.scrollWidth>document.documentElement.clientWidth+1}
   })
   rows.push({engine,browser:browser.version(),simulated:true,theme,viewport,dpr:options.deviceScaleFactor,...geometry})
   await page.screenshot({path:resolve(output,`${label}-${engine}-${theme}-${viewport.width}.png`)})
   if(!process.env.TOC_DIAGNOSTIC) {
    assert.ok(geometry.list.height>120,'目录列表应有可阅读高度，不能只检查 DOM link 存在')
    assert.ok(geometry.first.y>=geometry.list.y-1&&geometry.first.y<geometry.list.y+geometry.list.height,'首项实际位于可见区域')
    assert.ok(geometry.firstHit,'目录项必须能被触控命中，不能只有不可见 DOM')
    assert.ok(geometry.panel.y+geometry.panel.height<=viewport.height+1&&!geometry.overflow,'弹层位于可用视口')
    const list=page.getByRole('dialog').locator('.blog-toc-scroll');await list.evaluate(el=>el.scrollTop=el.scrollHeight)
    const last=page.getByRole('dialog').getByRole('link').last();await last.click();await page.getByRole('dialog').waitFor({state:'hidden'})
    assert.ok(await page.evaluate(()=>scrollY)>1000,'目录末项可滚动并实际点击定位正文')
    await page.evaluate(()=>scrollTo(0,0))
   }else {await page.keyboard.press('Escape');await page.getByRole('dialog').waitFor({state:'hidden'})}
  }
  await Promise.all(pendingWarnings);await context.close()
 }
 if(!process.env.TOC_DIAGNOSTIC)assert.deepEqual(errors,[])
}finally{
 await writeFile(resolve(output,`${label}-${engine}-results.json`),JSON.stringify({kind:'Engine simulation, NOT physical iPad',label,engine,browser:browser.version(),rows,errors,warnings},null,2))
 clearTimeout(timer);await browser.close();await server.close()
}
