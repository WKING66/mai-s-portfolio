import assert from 'node:assert/strict'
import {createRequire} from 'node:module'
import {mkdir,writeFile} from 'node:fs/promises'
import {resolve} from 'node:path'
import {execFileSync} from 'node:child_process'
const {chromium,webkit}=createRequire(import.meta.url)(process.env.PLAYWRIGHT_MODULE_PATH||'playwright')
const engine=process.env.MERMAID_VIEW_ENGINE||'edge',base=process.env.EXTERNAL_ACCEPTANCE_URL||'http://127.0.0.1:3000',output=resolve(process.env.MERMAID_VIEW_OUTPUT||'../docs/testing/screenshots/mermaid-pinch-20261013')
const capture=process.env.MERMAID_VIEW_CAPTURE!=='0'
if(capture) await mkdir(output,{recursive:true})
const server=await(engine==='webkit'?webkit:chromium).launchServer({headless:true,...(engine==='edge'?{executablePath:process.env.BROWSER_EXECUTABLE,args:['--renderer-process-limit=1','--js-flags=--max-old-space-size=512']}: {})})
const timer=setTimeout(()=>{execFileSync('taskkill',['/PID',String(server.process().pid),'/T','/F'],{windowsHide:true,stdio:'ignore'});process.exit(124)},180000)
const browser=await(engine==='webkit'?webkit:chromium).connect(server.wsEndpoint()),rows=[],errors=[]
const matrix=process.env.MERMAID_VIEW_ONLY_ARTICLE?[]:process.env.MERMAID_VIEW_WIDTHS?.split(',').map(Number)||(engine==='webkit'?[810,1080,390]:[1280,810,390,320])
async function reset(stage,page){await stage.scrollIntoViewIfNeeded();await stage.focus();await page.keyboard.press('0')}
async function transform(stage){return stage.locator('.blog-mermaid-plane').evaluate(el=>{const m=new DOMMatrixReadOnly(getComputedStyle(el).transform);return {scale:m.a,x:m.e,y:m.f}})}
async function fits(stage){return stage.evaluate(el=>{const box=el.getBoundingClientRect(),svg=el.querySelector('svg').getBoundingClientRect();return svg.width>10&&svg.width<=box.width+1&&svg.height<=box.height+1&&svg.left>=box.left-1&&svg.top>=box.top-1})}
// Edge 通过 CDP 发送浏览器原生多点触控；WebKit 做 DOM TouchEvent 处理路径回归。
async function pinchCheck(context,page,stage,engine,output,name){
 await stage.scrollIntoViewIfNeeded();const box=await stage.boundingBox(),center={x:box.x+box.width/2,y:box.y+box.height/2},initial=await transform(stage),scroll=await page.evaluate(()=>scrollY)
 const session=engine==='edge'?await context.newCDPSession(page):undefined
 async function send(type,points){
  if(session) await session.send('Input.dispatchTouchEvent',{type,touchPoints:points.map(point=>({...point,radiusX:2,radiusY:2}))})
  else await stage.evaluate((el,{type,points})=>{
   const touches=points.map(point=>({identifier:point.id,clientX:point.x,clientY:point.y,target:el})),event=new Event({touchStart:'touchstart',touchMove:'touchmove',touchEnd:'touchend',touchCancel:'touchcancel'}[type],{bubbles:true,cancelable:true})
   Object.defineProperties(event,{targetTouches:{value:touches},touches:{value:touches}});el.dispatchEvent(event)
  },{type,points})
  await page.waitForTimeout(25)
 }
 try{
  const first={id:0,x:center.x-30,y:center.y},second={id:1,x:center.x+30,y:center.y}
  await send('touchStart',[first]);await send('touchStart',[first,second])
  const expanded=[{id:0,x:center.x-60+12,y:center.y+8},{id:1,x:center.x+60+12,y:center.y+8}]
  await send('touchMove',expanded)
  const zoomed=await transform(stage)
  assert.ok(Math.abs(zoomed.scale-initial.scale*2)<.002,'双指张开放大，两指距离比控制比例')
  assert.ok(Math.abs((box.width/2-initial.x)/initial.scale-(box.width/2+12-zoomed.x)/zoomed.scale)<.02&&Math.abs((box.height/2-initial.y)/initial.scale-(box.height/2+8-zoomed.y)/zoomed.scale)<.02,'缩放锚定手势中心并支持双指平移')
  await send('touchEnd',[expanded[0]]);await send('touchMove',[{...expanded[0],x:expanded[0].x+20,y:expanded[0].y+20}])
  assert.deepEqual(await transform(stage),zoomed,'抬起一指后不跳转为拖拽')
  await send('touchEnd',[])
  assert.equal(await page.evaluate(()=>scrollY),scroll,'双指手势不滚动正文')
  if(session) assert.equal(await page.evaluate(()=>visualViewport.scale),1,'只缩放图表，不缩放网页')
  if(capture) await page.screenshot({path:resolve(output,name+'.png')})
  const next=[{id:0,x:center.x-60,y:center.y},{id:1,x:center.x+60,y:center.y}]
  await send('touchStart',[next[0]]);await send('touchStart',next)
  await send('touchMove',[{id:0,x:center.x-30,y:center.y},{id:1,x:center.x+30,y:center.y}]);await send('touchEnd',[])
  assert.ok(Math.abs((await transform(stage)).scale-initial.scale)<.002,'双指捏合缩小且支持重复手势')
 }finally{await session?.detach()}
}
try {
 for(const width of matrix)for(const theme of ['light','dark']){
  const context=await browser.newContext({viewport:{width,height:900},hasTouch:width<1200,reducedMotion:'reduce'})
  await context.addInitScript(()=>{window.__mermaidCounts={};window.__blogReadingProbe=name=>window.__mermaidCounts[name]=(window.__mermaidCounts[name]||0)+1;document.addEventListener('wheel',e=>{const stage=e.target.closest?.('.blog-mermaid-viewport');if(e.ctrlKey&&stage){const r=stage.getBoundingClientRect();window.__zoomPoint={x:e.clientX-r.left,y:e.clientY-r.top}}},{capture:true})})
  const page=await context.newPage();page.setDefaultTimeout(20000)
  page.on('pageerror',e=>errors.push(e.message));page.on('console',m=>{if(/hydration.*mismatch|Unable to preventDefault|Ignored attempt to cancel/i.test(m.text()))errors.push(m.text())})
  await page.goto(base+'/design-showcase');await page.locator('[data-showcase-theme]').waitFor()
  if(await page.locator('.design-showcase').getAttribute('data-showcase-theme')!==theme)await page.getByRole('button',{name:theme==='dark'?'预览深色主题':'预览浅色主题',exact:true}).click()
  const diagrams=page.locator('#mermaid-reading .blog-mermaid')
  // 先激活所有隔离样例，避免其他图表进入视口时的首次渲染污染交互计数。
  for(let index=0;index<6;index++) {const item=diagrams.nth(index);await item.scrollIntoViewIfNeeded();await item.locator(index===5?'pre':'svg').waitFor()}
  const diagram=diagrams.first(),stage=diagram.locator('.blog-mermaid-viewport')
  await diagram.scrollIntoViewIfNeeded();await stage.locator('svg').waitFor();await page.waitForTimeout(150)
  assert.ok(await fits(stage),'图表初始完整适配容器')
  const baseline=await transform(stage),count=await page.evaluate(()=>window.__mermaidCounts.mermaidRender)
  await diagram.getByRole('button',{name:'查看源码',exact:true}).click()
  assert.ok(await diagram.evaluate(el=>Boolean(el.querySelector('pre').compareDocumentPosition(el.querySelector('.blog-mermaid-viewport'))&Node.DOCUMENT_POSITION_FOLLOWING)),'源码位于图表上方')
  if(capture) await diagram.screenshot({path:resolve(output,`${engine}-${theme}-${width}-source-above.png`)})
  await diagram.getByRole('button',{name:'收起源码',exact:true}).click();await stage.scrollIntoViewIfNeeded()
  let box=await stage.boundingBox();await page.mouse.move(box.x+box.width/2,box.y+box.height/2)
  const regularScroll=await page.evaluate(()=>scrollY);await page.mouse.wheel(0,160);await page.waitForTimeout(300)
  assert.ok(await page.evaluate(()=>scrollY)>regularScroll,'普通滚轮继续阅读文章')
  assert.equal((await transform(stage)).scale,baseline.scale,'普通滚轮不改变图表比例')
  await stage.scrollIntoViewIfNeeded();box=await stage.boundingBox()
  const point={x:box.width*.35,y:box.height*.4},beforeWheel=await transform(stage)
  await page.mouse.move(box.x+point.x,box.y+point.y)
  const scroll=await page.evaluate(()=>scrollY)
  await page.keyboard.down('Control');await page.mouse.wheel(0,-100);await page.keyboard.up('Control');await page.waitForTimeout(80)
  const afterWheel=await transform(stage)
  assert.ok(afterWheel.scale>baseline.scale*1.1,'Ctrl滚轮改变图表比例')
  const actualPoint=await page.evaluate(()=>window.__zoomPoint)
  assert.ok(Math.abs((actualPoint.x-beforeWheel.x)/beforeWheel.scale-(actualPoint.x-afterWheel.x)/afterWheel.scale)<.01&&Math.abs((actualPoint.y-beforeWheel.y)/beforeWheel.scale-(actualPoint.y-afterWheel.y)/afterWheel.scale)<.01,'缩放围绕浏览器实际鼠标位置')
  assert.equal(await page.evaluate(()=>scrollY),scroll,'Ctrl滚轮不滚动文章')
  await reset(stage,page);assert.ok(await fits(stage))
  await stage.scrollIntoViewIfNeeded();box=await stage.boundingBox();await page.mouse.move(box.x+box.width/2,box.y+box.height/2)
  const inlineBefore=await transform(stage)
  await page.mouse.down();await page.mouse.move(box.x+box.width/2+35,box.y+box.height/2+30,{steps:5});await page.mouse.up()
  const inlineAfter=await transform(stage)
  console.log(JSON.stringify({engine,theme,width,inlineBefore,inlineAfter,box,scroll:await page.evaluate(()=>scrollY)}))
  assert.ok(Math.abs(inlineAfter.x-inlineBefore.x-35)<2&&Math.abs(inlineAfter.y-inlineBefore.y-30)<2,'正文拖拽平移')
  assert.equal(await page.getByRole('dialog').count(),0,'拖拽结束不误打开全屏')
  assert.equal(await diagram.getByRole('button',{name:'恢复尺寸',exact:true}).count(),0,'移除恢复尺寸按钮')
  assert.equal(await diagram.locator('.blog-mermaid-resize-handle').count(),0,'移除尺寸手柄')
  assert.equal(await diagram.locator('.blog-mermaid-tools').count(),0,'移除缩放工具栏与拖拽开关')
  await reset(stage,page)
  await page.setViewportSize({width:width===320?390:320,height:900});await page.waitForTimeout(100)
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'窄屏无整页溢出')
  await page.setViewportSize({width,height:900});await page.waitForTimeout(100)
  if(width<1200){
    await pinchCheck(context,page,stage,engine,output,`${engine}-${theme}-${width}-inline-pinch`)
    assert.equal(await page.getByRole('dialog').count(),0,'双指缩放和抬手不误打开全屏')
    await reset(stage,page)
    assert.equal(await page.getByRole('dialog').count(),0,'重置镜头不误进入全屏')
    assert.ok(await fits(stage),'镜头重新适配')
  }
  if(engine==='edge'&&width<1200){
    await stage.scrollIntoViewIfNeeded();const touchBox=await stage.boundingBox(),touch={x:touchBox.x+touchBox.width/2,y:touchBox.y+touchBox.height/2},beforeTouch=await transform(stage),beforeTouchScroll=await page.evaluate(()=>scrollY)
    const session=await context.newCDPSession(page)
    await session.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[touch]})
    await session.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:touch.x+30,y:touch.y+25}]})
    await session.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});await session.detach()
    const afterTouch=await transform(stage)
    assert.ok(Math.abs(afterTouch.x-beforeTouch.x-30)<2&&Math.abs(afterTouch.y-beforeTouch.y-25)<2,'正文触屏拖拽')
    assert.equal(await page.getByRole('dialog').count(),0,'触屏拖拽不误打开全屏')
    assert.equal(await page.evaluate(()=>scrollY),beforeTouchScroll,'单指在图内只移动图表')
    await reset(stage,page)
  }
  // 固定层遵循 Nuxt UI/现有稳定滚动条保留的布局宽度；锁定后 clientWidth 会改变。
  const available=await page.evaluate(()=>({width:document.documentElement.getBoundingClientRect().width,height:innerHeight,client:document.documentElement.clientWidth,body:document.body.getBoundingClientRect().width,gutter:getComputedStyle(document.documentElement).scrollbarGutter}))
  await stage.click();const modal=page.getByRole('dialog');await modal.waitFor()
  const fullscreen=modal.locator('.blog-mermaid-viewport');await fullscreen.locator('svg').waitFor();await page.waitForTimeout(100)
  assert.ok(await fits(fullscreen),'全屏重新适配可用空间')
  const modalBox=await modal.boundingBox()
  console.log(JSON.stringify({engine,theme,width,modalBox,available}))
  if(capture) await page.screenshot({path:resolve(output,`${engine}-${theme}-${width}-fullscreen-fit.png`)})
  assert.ok(modalBox.x>=-1&&modalBox.width>=available.width-1&&modalBox.height>=available.height-1)
  const close=modal.locator('[data-slot="close"]')
  assert.ok(await close.evaluate(el=>{const r=el.getBoundingClientRect();return document.elementFromPoint(r.x+r.width/2,r.y+r.height/2)?.closest('button')===el}),'全屏关闭按钮不能被网站Header遮挡')
  assert.equal(await modal.evaluate(el=>getComputedStyle(el).color),await diagram.evaluate(el=>getComputedStyle(el).color),'Portal中的控件继承图表局部主题')
  assert.ok(await page.evaluate(()=>{const ids=[...document.querySelectorAll('.blog-mermaid-plane [id]')].map(el=>el.id);return new Set(ids).size===ids.length}),'正文与全屏的SVG内部ID不冲突')
  const beforeZoom=await transform(fullscreen),fullBox=await fullscreen.boundingBox()
  await page.mouse.move(fullBox.x+fullBox.width/2,fullBox.y+fullBox.height/2);await page.keyboard.down('Control');await page.mouse.wheel(0,-100);await page.keyboard.up('Control');await page.waitForTimeout(80)
  assert.ok((await transform(fullscreen)).scale>beforeZoom.scale*1.1,'全屏Ctrl滚轮缩放')
  const beforeDrag=await transform(fullscreen)
  await page.mouse.down();await page.mouse.move(fullBox.x+fullBox.width/2+60,fullBox.y+fullBox.height/2+45,{steps:5});await page.mouse.up()
  const afterDrag=await transform(fullscreen);assert.ok(Math.abs(afterDrag.x-beforeDrag.x-60)<2&&Math.abs(afterDrag.y-beforeDrag.y-45)<2,'拖拽平移图表')
  if(engine==='edge'&&width<1200){
    const session=await context.newCDPSession(page),touch={x:fullBox.x+fullBox.width/2,y:fullBox.y+fullBox.height/2}
    await session.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[touch]})
    await session.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:touch.x+30,y:touch.y+25}]})
    await session.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});await session.detach()
    const afterTouch=await transform(fullscreen)
    assert.ok(Math.abs(afterTouch.x-afterDrag.x-30)<2&&Math.abs(afterTouch.y-afterDrag.y-25)<2,'模拟原生触摸拖拽')
  }
  if(width<1200) await pinchCheck(context,page,fullscreen,engine,output,`${engine}-${theme}-${width}-fullscreen-pinch`)
  if(capture) await page.screenshot({path:resolve(output,`${engine}-${theme}-${width}-fullscreen-pan.png`)})
  await reset(fullscreen,page);assert.ok(await fits(fullscreen))
  await fullscreen.focus();await page.keyboard.press('Equal');assert.ok((await transform(fullscreen)).scale>beforeZoom.scale,'键盘缩放')
  await page.keyboard.press('0');assert.ok(await fits(fullscreen))
  await page.keyboard.press('Escape');await modal.waitFor({state:'hidden'})
  assert.ok(await stage.evaluate(el=>document.activeElement===el),'关闭后焦点回到正文图表')
  await diagram.getByRole('button',{name:'全屏查看图表',exact:true}).click();await modal.waitFor();await close.click();await modal.waitFor({state:'hidden'})
  assert.equal(await page.evaluate(()=>window.__mermaidCounts.mermaidRender),count,'所有视图交互不再次解析Mermaid')
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1))
  rows.push({engine,browser:browser.version(),theme,viewport:{width,height:900},dpr:1,simulated:true,path:'/design-showcase#mermaid-reading',initial:baseline,inlineAfterDrag:inlineAfter,fullscreenAfterDrag:afterDrag})
  await context.close()
 }
 if(process.env.MERMAID_VIEW_ARTICLE){
  const context=await browser.newContext({viewport:{width:390,height:844},hasTouch:true,isMobile:true,reducedMotion:'reduce'}),page=await context.newPage()
  page.setDefaultTimeout(25000);page.on('pageerror',error=>errors.push(error.message));page.on('console',message=>{if(/hydration.*mismatch|Unable to preventDefault|Ignored attempt to cancel/i.test(message.text()))errors.push(message.text())})
  const path='/blog/'+encodeURIComponent(process.env.MERMAID_VIEW_ARTICLE)
  await page.goto(base+path)
  const diagram=page.locator('.blog-mermaid').first(),stage=diagram.locator('.blog-mermaid-viewport')
  // 真实长文按章节渐进挂载，前方内容布局就绪后需重新对准目标区域。
  for(let attempt=0;attempt<8;attempt++){
    await diagram.scrollIntoViewIfNeeded();await page.waitForTimeout(400)
    if(await stage.locator('svg').isVisible()) break
  }
  await stage.locator('svg').waitFor();await page.waitForTimeout(150)
  assert.ok(await fits(stage));assert.equal(await diagram.getByRole('button',{name:'恢复尺寸',exact:true}).count(),0)
  await pinchCheck(context,page,stage,engine,output,'published-inline-pinch')
  if(engine==='edge'){
    const box=await stage.boundingBox(),point={x:box.x+box.width/2,y:box.y+box.height/2},before=await transform(stage),scroll=await page.evaluate(()=>scrollY),session=await context.newCDPSession(page)
    await session.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[point]})
    await session.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:point.x+25,y:point.y+20}]})
    await session.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});await session.detach()
    const after=await transform(stage)
    assert.ok(Math.abs(after.x-before.x-25)<2&&Math.abs(after.y-before.y-20)<2,'公开文章单指直接移动')
    assert.equal(await page.evaluate(()=>scrollY),scroll)
  }
  assert.equal(await page.getByRole('dialog').count(),0)
  await reset(stage,page);assert.ok(await fits(stage));assert.equal(await page.getByRole('dialog').count(),0)
  await diagram.evaluate(el=>window.scrollBy(0,el.getBoundingClientRect().top-(document.querySelector('header')?.getBoundingClientRect().height||0)-8))
  if(capture) await diagram.screenshot({path:resolve(output,'published-controls.png')})
  await diagram.getByRole('button',{name:'全屏查看图表',exact:true}).tap();const modal=page.getByRole('dialog');await modal.waitFor()
  const full=modal.locator('.blog-mermaid-viewport');await full.locator('svg').waitFor();await page.waitForTimeout(100)
  await pinchCheck(context,page,full,engine,output,'published-fullscreen-pinch');await modal.locator('[data-slot="close"]').tap();await modal.waitFor({state:'hidden'})
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1))
  rows.push({engine,browser:browser.version(),viewport:{width:390,height:844},dpr:1,simulated:true,isMobile:true,theme:await page.evaluate(()=>document.documentElement.dataset.theme),path,published:true})
  await context.close()
 }
 assert.deepEqual(errors,[])
}finally{if(capture) await writeFile(resolve(output,engine+'-results.json'),JSON.stringify({rows,errors},null,2));console.log(JSON.stringify({engine,groups:rows.length,errors}));clearTimeout(timer);await browser.close();await server.close()}
