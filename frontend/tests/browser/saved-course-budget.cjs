// Local sample data only; no production API or data changes.
const {chromium}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert=require('node:assert/strict');
const {join}=require('node:path');
const {tmpdir}=require('node:os');
const base=process.env.SAVED_COURSES_TEST_URL || 'http://127.0.0.1:5210';
(async()=>{
 const browser=await chromium.launch({headless:true});
 try {
  for(const mobile of [false,true]) {
   const context=await browser.newContext({viewport:mobile?{width:390,height:844}:{width:1440,height:1000},isMobile:mobile,hasTouch:mobile});
   const page=await context.newPage();
   await page.goto(base+'/tests/browser/saved-courses.html?budget=1');
   const cards=page.locator('.library-list .course-tab');
   await cards.first().waitFor();
   assert.doesNotMatch(await cards.nth(0).innerText(),/예산|100,000원/);
   assert.doesNotMatch(await cards.nth(1).innerText(),/예산|200,000원/);
   await cards.first().click();
   await page.locator('.course-summary').waitFor();
   assert.equal(await page.locator('.saved-budget').count(),0);
   assert.doesNotMatch(await page.locator('.course-summary').innerText(),/예산|100,000원/);
   const path=join(tmpdir(),`hangat-budget-${mobile?'mobile':'desktop'}.png`);
   await page.screenshot({path});
   console.log(`${mobile?'mobile':'desktop'} budget PASS ${path}`);
   await context.close();
  }
 } finally {await browser.close()}
})().catch(e=>{console.error(e);process.exit(1)});
