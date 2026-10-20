const {chromium}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');const assert=require('node:assert/strict');const {join}=require('node:path');const {tmpdir}=require('node:os');
(async()=>{const browser=await chromium.launch({headless:true});try{
 const page=await browser.newPage({viewport:{width:1280,height:940},isMobile:true,hasTouch:true});let requests=0;
 await page.route('**/auth/demo-login',async r=>{requests++;assert.equal(r.request().method(),'POST');assert.equal(r.request().postData(),null);await new Promise(resolve=>setTimeout(resolve,200));await r.fulfill({json:{success:true,result:{user:{userId:7,nickname:'한갓지도 데모계정',demoAccount:true},tokens:{accessToken:'test-only',expiresIn:60000}}}})});
 await page.goto('http://127.0.0.1:5208/tests/browser/demo-login.html?redirect=/courses');
 const button=page.getByRole('button',{name:/한갓지도 데모계정으로 바로 로그인/});await button.waitFor();
 assert.equal(await page.getByText('빠른 체험 모드',{exact:true}).count(),0);
 await page.waitForFunction(()=>document.querySelector('.demo-avatar img')?.naturalWidth>0);
 await page.waitForFunction(()=>!document.getAnimations().some(a=>a.playState==='running'));
 await page.screenshot({path:join(tmpdir(),'demo-login-desktop.png')});
 await page.setViewportSize({width:390,height:844});await page.screenshot({path:join(tmpdir(),'demo-login-mobile.png'),fullPage:true});
 assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
 await button.click();assert.equal(await page.locator('.demo-login').isDisabled(),true);
 await page.getByRole('heading',{name:'로그인 후 이동 화면'}).waitFor({timeout:5000}).catch(async e=>{console.log(await page.locator('body').innerText(), 'requests',requests, 'path',await page.evaluate(()=>window.qaRouter.currentRoute.value.path));throw e});assert.equal(await page.evaluate(()=>window.qaRouter.currentRoute.value.path),'/courses');assert.equal(requests,1);
 await page.route('**/auth/demo-login',r=>r.fulfill({status:503,json:{success:false,message:'unavailable'}}));
 await page.goto('http://127.0.0.1:5208/tests/browser/demo-login.html');await button.click();await page.getByRole('alert').waitFor();assert.equal(await button.isDisabled(),false);
 console.log('Demo login card, image, mobile, pending state, redirect and error checks passed');
}finally{await browser.close()}})().catch(e=>{console.error(e);process.exit(1)});
