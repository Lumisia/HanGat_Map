const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const { join } = require('node:path');
const { tmpdir } = require('node:os');
(async () => {
 const browser = await chromium.launch({headless:true});
 try {
  for (const width of [1440,390,320]) {
   const page = await browser.newPage({viewport:{width,height:1000},isMobile:width<768,hasTouch:width<768});
   const errors=[];page.on('pageerror',e=>errors.push(e.message));
   await page.goto('http://127.0.0.1:5211/tests/browser/ai-builder.html?nav');
   const nav=page.getByRole('navigation',{name:'코스 조건 입력 단계'});
   if(width<1024){await nav.getByRole('button',{name:'4 필터'}).click();await page.getByText('여정 확인 →',{exact:true}).click();}
   await page.getByRole('button',{name:'AI 코스 만들기',exact:true}).click();
   await page.locator('.step-3').waitFor();
   assert.equal(await page.evaluate(()=>window.generationCalls),0);
   await page.getByRole('button',{name:'자연',exact:true}).click();
   if(width<1024){await nav.getByRole('button',{name:'4 필터'}).click();await page.getByText('여정 확인 →',{exact:true}).click();}
   await page.getByRole('button',{name:'AI 코스 만들기',exact:true}).click();
   await page.locator('.result-shell').waitFor();
   const tabs=page.getByRole('tab');
   assert.equal(await tabs.count(),3);
   assert.equal(await page.locator('.course-day:visible').count(),1);
   await tabs.nth(1).click();
   assert.equal(await tabs.nth(1).getAttribute('aria-selected'),'true');
   await tabs.nth(1).press('ArrowRight');
   assert.equal(await tabs.nth(2).getAttribute('aria-selected'),'true');
   await tabs.nth(2).press('Home');
   assert.equal(await tabs.first().getAttribute('aria-selected'),'true');
   await tabs.nth(1).click();
   assert.ok(await page.getByRole('heading',{name:'2일차 김녕요트투어',exact:true}).isVisible());
   assert.ok(await page.getByLabel('강수확률 0%',{exact:true}).isVisible());
   assert.equal(await page.locator('.result-actions-row button').count(),4);
   assert.equal(await page.locator('.result-actions').count(),0);
   assert.equal(await page.locator('.result-actions-block .temporary-course-notice').count(),1);
   await page.getByRole('button',{name:'지도에서 보기',exact:true}).click();
   assert.ok(await page.getByText('코스를 저장한 뒤 지도에서 확인해 주세요.',{exact:true}).isVisible());
   const notice = await page.locator('.ai-course-page .toast').boundingBox();
   assert.ok(notice.x >= 12 && notice.x + notice.width <= width - 12, 'Notice stays within viewport');
   if(width<768){
    const bar=await page.locator('.mtabbar').boundingBox();
    assert.ok(notice.y + notice.height <= bar.y - 12, 'Notice must be fully above the mobile navigation');
   }
   assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
   assert.deepEqual(errors,[]);
   await tabs.first().click();
   await page.screenshot({path:join(tmpdir(),`ai-result-${width}.png`),fullPage:true});
   console.log(`AI result ${width}px PASS`);
   await page.close();
  }
 } finally { await browser.close(); }
})().catch(e=>{console.error(e);process.exit(1)});
