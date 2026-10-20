const {chromium}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({headless:true});
 try {
  for(const width of [1440,390]) {
   const page=await browser.newPage({viewport:{width,height:950}});
   await page.goto('http://127.0.0.1:5211/tests/browser/mypage.html?tab=favorites&missing-images');
   await page.locator('.cards .card').first().waitFor();
   for(const theme of ['light','dark']) {
    await page.evaluate(t=>document.documentElement.dataset.theme=t,theme);
    for(const mode of ['카드','목록']) {
     await page.getByRole('button',{name:mode,exact:true}).click();
     if(mode==='목록') { assert.ok(await page.locator('.rows .rn').first().isVisible()); continue; }
     const thumbs=page.locator('.thumb');
     for(const i of [0,1]) {
      const img=thumbs.nth(i).locator('img');
      await img.waitFor({timeout:3000});
      await page.waitForFunction(index=>{
       const image=document.querySelectorAll('.thumb')[index]?.querySelector('img');
       return image?.src.endsWith('/images/placeholder.svg') && image.complete && image.naturalWidth>0;
      },i,{timeout:3000});
      assert.match(await img.getAttribute('alt'),/이미지 준비 중/);
     }
     assert.match(await thumbs.nth(2).locator('img').getAttribute('src'),/gwangchigi/);
     await page.screenshot({path:require('node:path').join(require('node:os').tmpdir(),`favorite-images-${theme}-${width}.png`),fullPage:true});
    }
   }
   await page.close();console.log(`Favorite image fallback ${width}px PASS`);
  }
 } finally {await browser.close()}
})().catch(e=>{console.error(e);process.exit(1)});
