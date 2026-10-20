// Local UI fixture only; never calls production routing or mutates production courses.
const {chromium}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert=require('node:assert/strict');
const {join}=require('node:path');const {tmpdir}=require('node:os');
const base=process.env.SAVED_COURSES_TEST_URL || 'http://127.0.0.1:5210';
(async()=>{
 const browser=await chromium.launch({headless:true});
 try {
  for(const mobile of [false,true]) {
   const context=await browser.newContext({viewport:mobile?{width:390,height:844}:{width:1440,height:1000},isMobile:mobile,hasTouch:mobile});
   const page=await context.newPage();
   await page.goto(base+'/tests/browser/saved-courses.html');
   await page.locator('.library-list .course-tab').first().click();
   await page.getByRole('button',{name:'DAY 1 일정 수정',exact:true}).click();
   const open=page.locator('.itinerary-day').first().getByRole('button',{name:/ 대안 보기$/}).first();
   await open.click();
   await page.locator('.alt-list article').first().waitFor();
   assert.equal(await page.locator('.alt-list article').count(),3);
   assert.match(await page.locator('.alternative-header').innerText(),/직선거리 20km/);
   assert.equal(await page.locator('.alternative-map').first().getAttribute('href'),'/map?place=99');
   assert.equal(await page.locator('.alternative-map').first().getAttribute('target'),'_blank');
   assert.match(await page.locator('.alternative-overview').first().innerText(),/해안 풍경/);
   await page.waitForFunction(()=>document.querySelector('.alternative-photo img')?.naturalWidth>0);
   const photo=await page.locator('.alternative-photo').first().boundingBox();
   const copy=await page.locator('.alternative-copy').first().boundingBox();
   const change=await page.locator('.select-alternative').first().boundingBox();
   const map=await page.locator('.alternative-map').first().boundingBox();
   assert.ok(photo.x>=copy.x+copy.width,'image is to the right of introduction');
   assert.ok(change.x>map.x,'change action is to the right of map action');
   assert.ok(await page.locator('.alternative-modal').evaluate(el=>el.scrollWidth<=el.clientWidth),'no horizontal modal overflow');
   await page.screenshot({path:join(tmpdir(),`alternatives-${mobile?'mobile':'desktop'}.png`)});
   const list=page.locator('.alternative-scroll');
   // On a tall desktop, a keyboard-accessible button covers the no-scroll case.
   if(await list.evaluate(el=>el.scrollHeight>el.clientHeight)) {
    await list.evaluate(el=>{el.scrollTop=el.scrollHeight;el.dispatchEvent(new Event('scroll'))});
   } else await page.getByRole('button',{name:'다음 대안 3곳 보기'}).click();
   await page.waitForFunction(()=>document.querySelectorAll('.alt-list article').length>=6);
   await list.evaluate(el=>{el.scrollTop=el.scrollHeight;el.dispatchEvent(new Event('scroll'))});
   await page.getByText('더 이상 가능한 장소 대안이 없습니다.',{exact:true}).waitFor();
   assert.equal(await page.locator('.alt-list article').count(),7);
   for(let i=0;i<4;i++)await list.evaluate(el=>el.dispatchEvent(new Event('scroll')));
   assert.equal(await page.evaluate(()=>window.qaAlternativeCalls.length),1,'scrolling never refetches alternatives');
   await page.keyboard.press('Escape');
   assert.equal(await page.locator('.alternative-modal').count(),0);
   if(mobile){
    const handle=page.locator('.sheet-handle');await handle.press('End');
    await page.waitForFunction(()=>{
     const s=document.querySelector('.itinerary-sheet'), h=document.querySelector('.course-browser');
     return !s.getAnimations().some(a=>a.playState==='running') && Math.abs(s.getBoundingClientRect().top-h.getBoundingClientRect().bottom)<2;
    });
    await page.screenshot({path:join(tmpdir(),'saved-mobile-max.png')});
    await handle.click();assert.equal(await handle.getAttribute('aria-expanded'),'false');
    await handle.click();assert.equal(await handle.getAttribute('aria-expanded'),'true');
   }
   await context.close();
  }
  console.log('Straight-line alternative desktop/mobile layout, pagination, request counts and sheet bounds passed');
 } finally {await browser.close()}
})().catch(e=>{console.error(e);process.exit(1)});
