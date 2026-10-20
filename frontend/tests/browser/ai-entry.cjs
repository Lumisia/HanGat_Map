const {chromium}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({headless:true});
 try{
  for(const width of [1440,390]){
   const page=await browser.newPage({viewport:{width,height:950},isMobile:width<768,hasTouch:width<768});
   await page.goto('http://127.0.0.1:5211/tests/browser/ai-builder.html?nav&preview=result');
   await page.locator('.result-shell').waitFor();
   const navLink=page.locator(width>767?'.nav .tab':'.mtabbar .mt').filter({hasText:'AI코스'});
   await navLink.click();
   await page.locator('.stitch-builder.step-1').waitFor({timeout:3000});
   assert.equal(await page.locator('.result-shell').count(),0);
   assert.equal(await page.locator('[data-error-target="people"] input').inputValue(),'2');
   await page.getByRole('navigation',{name:'코스 조건 입력 단계'}).getByRole('button',{name:'3 취향'}).click();
   assert.equal(await page.getByRole('button',{name:'자연',exact:true}).getAttribute('aria-pressed'),'true');
   await navLink.click();
   await page.locator('.stitch-builder.step-1').waitFor();
   // An old result remembered in this tab must not replace the condition form on entry.
   await page.evaluate(()=>{const key='hangat.ai-course.restore.v1';const state=JSON.parse(sessionStorage.getItem(key));sessionStorage.setItem(key,JSON.stringify({...state,mode:'result',courseId:991}));});
   await page.goto('http://127.0.0.1:5211/tests/browser/ai-builder.html?nav&resume');
   await page.locator('.stitch-builder.step-1').waitFor({timeout:3000});
   assert.equal(await page.locator('.result-shell').count(),0);
   console.log(`AI menu entry ${width}px PASS`);
   await page.close();
  }
 }finally{await browser.close()}
})().catch(e=>{console.error(e);process.exit(1)});
