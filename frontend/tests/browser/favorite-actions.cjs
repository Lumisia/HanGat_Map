const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const assert=require('node:assert/strict');
(async()=>{const browser=await chromium.launch();try{
 for(const width of [1280,390]){
 const page=await browser.newPage({viewport:{width,height:844},isMobile:width<500,hasTouch:width<500});
 await page.goto('http://127.0.0.1:5211/tests/browser/mypage.html?tab=favorites');
 for(const mode of ['카드','목록']){
 await page.getByRole('button',{name:mode,exact:true}).click();
 const box=page.locator(mode==='카드'?'.cards > li':'.rows > li').first();
 await box.getByRole('button',{name:/메뉴/}).click({timeout:5000});
 const links=box.getByRole('link');assert.equal(await links.count(),2);
 assert.equal(await links.nth(0).innerText(),'지도에서 보기');
 assert.equal(await links.nth(1).innerText(),'장소 상세 보기');
 assert.match(await links.nth(0).getAttribute('href'),/\/map\?place=1$/);
 assert.match(await links.nth(1).getAttribute('href'),/\/places\/1$/);
 assert.equal(await page.locator('.favorites-kakao-map').count(),0);
 }
 await page.evaluate(()=>window.qaFailFavoriteDelete=true);
 await page.getByRole('button',{name:'금오름 찜 해제',exact:true}).click();
 await page.getByRole('alert').filter({hasText:'테스트 찜 해제 실패'}).waitFor();
 assert.equal(await page.getByRole('button',{name:'금오름 메뉴',exact:true}).count(),1);
 await page.getByRole('button',{name:'금오름 찜 해제',exact:true}).click();
 await page.getByRole('button',{name:'금오름 메뉴',exact:true}).waitFor({state:'detached'});
 assert.ok(await page.evaluate(w=>document.documentElement.scrollWidth<=w,width));
 await page.close();
 }console.log('Favorite card/list actions, real ID routes, delete success/failure and mobile passed');
}finally{await browser.close()}})().catch(e=>{console.error(e);process.exit(1)});
