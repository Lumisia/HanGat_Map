const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const assert=require('node:assert/strict');
const {join}=require('node:path');const {tmpdir}=require('node:os');
(async()=>{const browser=await chromium.launch();try{
 for(const width of [1100,390]) for(const kind of ['404','share','theme','error']){
  const page=await browser.newPage({viewport:{width,height:760},hasTouch:width<500,isMobile:width<500});
  await page.goto(`http://127.0.0.1:5211/tests/browser/unavailable.html?kind=${kind}`);
  if(kind==='error'){
   await page.getByRole('button',{name:'다시 시도',exact:true}).waitFor();
   assert.equal(await page.locator('.unavailable-card').count(),0);
  }else{
   const card=page.locator('.unavailable-card');await card.waitFor({timeout:5000});
   await page.waitForFunction(()=>{const i=document.querySelector('.unavailable-card img');return i?.complete&&i.naturalWidth>0});
   assert.equal(await card.getByRole('heading',{level:1}).count(),1);
   assert.ok(await page.evaluate(w=>document.documentElement.scrollWidth<=w,width));
   const target=kind==='404'?'/mypage':kind==='share'?'/ai-course':'/themes';
   assert.equal(await card.getByRole('link').first().getAttribute('href'),target);
   if(kind==='404') await page.screenshot({path:join(tmpdir(),`hangat-unavailable-${width}.png`)});
  }
  await page.close();
 }
 console.log('404/share/theme cards, image, links, mobile layout and transient share error passed');
}finally{await browser.close()}})().catch(e=>{console.error(e);process.exit(1)});
