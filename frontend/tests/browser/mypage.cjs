const {chromium}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert=require('node:assert/strict');
const {join}=require('node:path');
const {tmpdir}=require('node:os');
(async()=>{
 const browser=await chromium.launch({headless:true});
 try {
  let page=await browser.newPage({viewport:{width:1440,height:1000}});
  await page.goto(process.env.MYPAGE_PREVIEW_URL || 'http://127.0.0.1:5211/tests/browser/mypage.html');
  await page.getByRole('link',{name:'성이시돌목장',exact:true}).first().waitFor();
  await page.waitForFunction(()=>document.querySelector('.stats')?.textContent.includes('3개'),null,{timeout:5000});
  assert.match(await page.locator('.stats').innerText(), /작성한 리뷰\s*2\s*개/);
  const profile=await page.locator('.hero').boundingBox();
  const content=await page.locator('.body').boundingBox();
  assert.ok(profile.x+profile.width<=content.x,'Desktop profile must sit beside, not above, tab content');
  assert.ok(Math.abs(profile.y-content.y)<3,'Desktop profile and content must share a top edge');
  const assertProfile = async () => {
   const photo=await page.locator('.profile-top .profile-photo').boundingBox();
   const intro=await page.locator('.profile-intro').boundingBox();
   assert.ok(intro.x>=photo.x+photo.width,'Name and description sit beside photo');
   assert.ok(Math.abs(intro.y-photo.y)<16,'Name starts at photo top');
   assert.equal(await page.getByText('조용한 여행자',{exact:true}).count(),0);
   await page.waitForFunction(()=>document.querySelector('a[href="/mypage/favorites"] .nav-count')?.textContent==='15');
  };
  await assertProfile();
  await page.screenshot({path:join(tmpdir(),'hangat-mypage-desktop-final.png'),fullPage:true});
  for(const width of [390,320]){
   await page.close();
   page=await browser.newPage({viewport:{width,height:844},isMobile:true,hasTouch:true});
   await page.goto(process.env.MYPAGE_PREVIEW_URL || 'http://127.0.0.1:5211/tests/browser/mypage.html');
   await page.getByRole('link',{name:'성이시돌목장',exact:true}).first().waitFor();
   await assertProfile();
   assert.ok(await page.evaluate(w=>document.documentElement.scrollWidth<=w,width),'No horizontal overflow');
   for(const label of ['찜한 장소','알림 내역','설정','작성한 리뷰']){
    await page.getByRole('navigation',{name:'마이페이지 메뉴'}).getByRole('link',{name:label}).tap();
    if(label==='찜한 장소'){
     await page.getByRole('combobox',{name:'찜 정렬 기준'}).selectOption('name');
     assert.equal(await page.getByRole('combobox',{name:'찜 정렬 기준'}).inputValue(),'name');
    }
    assert.ok(await page.evaluate(w=>document.documentElement.scrollWidth<=w,width),`${label} fits ${width}px`);
   }
  }
  assert.equal(await page.getByRole('button',{name:'사진 변경',exact:true}).count(),0);
  await page.evaluate(()=>document.documentElement.dataset.theme='dark');
  assert.equal(await page.locator('.mypage').evaluate(e=>getComputedStyle(e).backgroundColor),'rgb(30, 41, 47)');
  assert.equal(await page.evaluate(()=>getComputedStyle(document.documentElement).getPropertyValue('--mp-bg').trim()),'','MyPage dark tokens must not leak globally');
  await page.close();
  page=await browser.newPage({viewport:{width:390,height:844},isMobile:true,hasTouch:true});
  await page.goto(process.env.MYPAGE_PREVIEW_URL || 'http://127.0.0.1:5211/tests/browser/mypage.html');
  await page.waitForFunction(()=>document.querySelector('.stats')?.textContent.includes('3개'),null,{timeout:5000});
  // Capture the changed page only. Chromium screenshot resets touch emulation,
  // so no interaction assertions rely on the device state after capture.
  await page.locator('.mypage').screenshot({path:join(tmpdir(),'hangat-mypage-mobile-content.png')});
  console.log('MyPage desktop alignment, mobile tabs, 320/390px overflow and demo profile lock passed');
 }finally{await browser.close()}
})().catch(e=>{console.error(e);process.exit(1)});
