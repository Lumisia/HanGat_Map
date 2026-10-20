const {chromium}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert=require('node:assert/strict');
const {resolve,join}=require('node:path');
const {tmpdir}=require('node:os');
(async()=>{
 const browser=await chromium.launch({headless:true});
 try {
  const page=await browser.newPage({viewport:{width:1100,height:760},isMobile:true,hasTouch:true});
  await page.route('**/users/7/profile-image/*',r=>r.fulfill({path:resolve('../backend/src/main/resources/images/demo-profile-v1.png'),contentType:'image/png'}));
  await page.route('**/notifications/**',r=>r.fulfill({json:{success:true,result:{items:[],unreadCount:0}}}));
  await page.goto('http://127.0.0.1:5208/tests/browser/demo-profile.html');
  await page.getByText('데모 계정의 프로필 사진은 변경할 수 없어요.').waitFor();
  await page.waitForFunction(()=>{const imgs=[...document.querySelectorAll('img[src*="profile-image"]')];return imgs.length>=3&&imgs.every(i=>i.complete&&i.naturalWidth>0)});
  assert.equal(await page.getByRole('button',{name:'사진 변경',exact:true}).count(),0);
  assert.equal(await page.locator('input[type=file]').count(),0);
  await page.screenshot({path:join(tmpdir(),'demo-profile-desktop.png')});
  await page.setViewportSize({width:390,height:844});
  await page.screenshot({path:join(tmpdir(),'demo-profile-mobile.png')});
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
  await page.screenshot({path:join(tmpdir(),'demo-profile-mobile.png')});
  console.log('Demo profile: header, editor, review avatar, upload controls and mobile checks passed');
 } finally {await browser.close()}
})().catch(e=>{console.error(e);process.exit(1)});
