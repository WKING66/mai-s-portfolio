import assert from 'node:assert/strict'
import {createRequire} from 'node:module'
import {execFileSync} from 'node:child_process'
import {mkdir,writeFile} from 'node:fs/promises'
import {resolve} from 'node:path'
const {chromium}=createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE_PATH||'playwright')
// 只读真实后端，不注入接口 fixture，不提交任何业务写入。
const base=process.env.EXTERNAL_ACCEPTANCE_URL||'http://127.0.0.1:3000',api=[],rows=[],errors=[]
const output=resolve(process.env.FRONTEND_BASELINE_OUTPUT||'../.local-backups/frontend-baseline')
await mkdir(output,{recursive:true})
async function get(path){const response=await fetch(base+path,{signal:AbortSignal.timeout(15000)}),value=await response.json();api.push({path,status:response.status,code:value.code});assert.equal(response.status,200);assert.equal(value.code,'OK');return value.data}
const profile=await get('/api/v1/profile'),projects=await get('/api/v1/projects?view=PUBLIC&page=1&size=12'),blogs=await get('/api/v1/blogs?view=PUBLIC&page=1&size=12'),session=await get('/api/v1/auth/session')
assert.ok(profile.displayName);assert.equal(projects.view,'PUBLIC');assert.equal(blogs.view,'PUBLIC');assert.ok(Array.isArray(blogs.items))
const article=blogs.items.find(x=>x.title.includes('数据结构与算法'));assert.ok(article)
const body=await get('/api/v1/blogs/by-slug/'+encodeURIComponent(article.slug));assert.equal(body.status,'PUBLISHED');assert.ok(body.content.includes('#'));assert.ok(body.documentVersionId);assert.ok(Array.isArray(body.resources))
assert.equal(session.loggedIn,false)
const server=await chromium.launchServer({headless:true,executablePath:process.env.BROWSER_EXECUTABLE,args:['--renderer-process-limit=1','--js-flags=--max-old-space-size=512']})
const timer=setTimeout(()=>{execFileSync('taskkill',['/PID',String(server.process().pid),'/T','/F'],{windowsHide:true,stdio:'ignore'});process.exit(124)},180000),browser=await chromium.connect(server.wsEndpoint())
try{
 for(const width of [1280,390])for(const theme of ['light','dark']){
  const context=await browser.newContext({viewport:{width,height:900},hasTouch:width<1000,reducedMotion:'reduce'})
  await context.addCookies([{name:'portfolio-theme',value:theme,url:base}])
  await context.addInitScript(theme=>localStorage.setItem('portfolio-theme',theme),theme)
  await context.route('**/api/v1/**',route=>['GET','HEAD'].includes(route.request().method())?route.continue():route.abort())
  const page=await context.newPage();page.setDefaultTimeout(25000)
  page.on('pageerror',e=>errors.push(e.message));page.on('console',m=>{if(/hydration.*mismatch/i.test(m.text()))errors.push(m.text())})
  for(const path of ['/','/projects','/blog','/login','/register','/forbidden','/__baseline_missing_page']){
   const response=await page.goto(base+path);await page.locator('main').waitFor();await page.waitForTimeout(100)
   assert.equal(response.status(),path==='/__baseline_missing_page'?404:200);assert.equal(await page.evaluate(()=>document.documentElement.dataset.theme),theme)
   assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'页面无横向溢出')
   rows.push({width,theme,path,status:response.status(),realApi:true})
  }
  await page.goto(base+'/blog');const link=page.getByRole('link').filter({hasText:'数据结构与算法'}).first();await link.click()
  const renderer=page.locator('.blog-renderer[data-reading-ready="true"]');await renderer.waitFor()
  if(width===1280&&theme==='light'){
   const code=renderer.locator('.blog-code').first();await code.scrollIntoViewIfNeeded()
   await code.getByRole('combobox').click();await page.getByRole('option',{name:'Dracula',exact:true}).click();assert.equal(await code.getAttribute('data-code-theme'),'dracula')
   await code.getByRole('combobox').click();await page.getByRole('option',{name:'GitHub Light',exact:true}).click();assert.equal(await code.getAttribute('data-code-theme'),'light')
   rows.push({width,theme,path:'/blog/'+article.slug,realApi:true,interaction:'Shiki Dracula/GitHub Light with portal select'})
  }
  const chapter=renderer.locator('.blog-chapter-heading button').first();await chapter.scrollIntoViewIfNeeded();await chapter.click();await chapter.click()
  if(width<1000){await page.getByRole('button',{name:'打开文章目录',exact:true}).click();const dialog=page.getByRole('dialog');await dialog.waitFor();assert.ok(await dialog.getByRole('link').count());await page.keyboard.press('Escape');await dialog.waitFor({state:'hidden'})}
  await page.reload();await renderer.waitFor();assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1))
  rows.push({width,theme,path:'/blog/'+article.slug,realApi:true,interaction:'list click, chapter fold/unfold, SSR reload, mobile TOC'})
  await page.goto(base+'/admin/blogs');await page.waitForURL('**/login**');rows.push({width,theme,path:'/admin/blogs',anonymousRedirect:'login',realApi:true})
  await context.close()
 }
 assert.deepEqual(errors,[])
}finally{await writeFile(resolve(output,'live-results.json'),JSON.stringify({api,rows,errors,scope:'真实公开 API 与匿名路由，只读，无请求 Mock，无真实写入'},null,2));clearTimeout(timer);await browser.close();await server.close()}
console.log(JSON.stringify({apiChecks:api.length,pageChecks:rows.length,errors}))
