const {chromium}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert=require('node:assert/strict');
const {join}=require('node:path');
const {tmpdir}=require('node:os');
const base=process.env.AI_BUILDER_TEST_URL || 'http://127.0.0.1:5211';
(async()=>{
 const browser=await chromium.launch({headless:true});
 try {
  for(const width of [1440,390]) {
   const page=await browser.newPage({viewport:{width,height:1000}});
   const errors=[];page.on('pageerror',e=>errors.push(e.message));
   await page.goto(base+'/tests/browser/ai-builder.html');
   await page.locator('.builder-progress').waitFor();
   assert.equal(await page.getByText('전체 예산',{exact:true}).count(),0);
   await page.screenshot({path:join(tmpdir(),`ai-builder-${width}.png`),fullPage:true});
   const nav=page.getByRole('navigation',{name:'코스 조건 입력 단계'});
   await nav.getByRole('button',{name:'2 권역'}).click();
   await page.getByRole('button',{name:'동부',exact:true}).click();
   await nav.getByRole('button',{name:'3 취향'}).click();
   await page.getByRole('button',{name:'자연',exact:true}).click();
   await nav.getByRole('button',{name:'4 필터'}).click();
   assert.ok(await page.getByRole('heading',{name:'꼭 가고 싶은 장소 (선택)'}).isVisible());
   if(width<1024) await page.getByRole('button',{name:'여정 확인 →'}).click();
   assert.ok(await page.getByRole('button',{name:'AI 코스 만들기',exact:true}).isEnabled());
   assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
   assert.deepEqual(errors,[]);
   await page.evaluate(()=>window.failGeneration());
   await page.getByRole('button',{name:'AI 코스 만들기',exact:true}).click();
   await page.locator('.generation-failure').waitFor();
   assert.equal(await page.locator('.generation-failure img').count(),0, 'Inline generation failure must not show the job-page artwork');
   assert.ok(await page.locator('.stitch-builder').isVisible());
   await page.goto(base+'/tests/browser/ai-builder.html?status=RUNNING');
   const slide=()=>page.locator('.slide-window img').last();
   await slide().waitFor();
   assert.match(await slide().getAttribute('src'),/wait-1/);
   await page.waitForTimeout(3100);
   assert.match(await slide().getAttribute('src'),/wait-2/);
   await page.getByRole('button',{name:'일시정지'}).click();
   await page.waitForTimeout(3200);
   assert.match(await slide().getAttribute('src'),/wait-2/);
   await page.evaluate(()=>window.setArtworkStatus('SUCCEEDED'));
   await page.locator('.is-result > img').waitFor();
   assert.match(await page.locator('.is-result > img').getAttribute('src'),/success/);
   await page.evaluate(()=>window.setArtworkStatus('FAILED'));
   await page.waitForTimeout(50);
   assert.match(await page.locator('.is-result > img').getAttribute('src'),/failure/);
   await page.emulateMedia({reducedMotion:'reduce'});
   await page.evaluate(()=>window.setArtworkStatus('RUNNING'));
   await page.waitForFunction(()=>document.querySelector('.pause')?.disabled);
   assert.ok(await page.getByRole('button',{name:'재생',exact:true}).isDisabled());
   assert.deepEqual(errors,[]);
   console.log(`AI builder + artwork ${width}px PASS`);
   await page.close();
  }
 } finally {await browser.close()}
})().catch(e=>{console.error(e);process.exit(1)});
