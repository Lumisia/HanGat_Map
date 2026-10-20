const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const assert=require('node:assert/strict');
(async()=>{const browser=await chromium.launch();try{
 for(const width of [1280,390]){
  const page=await browser.newPage({viewport:{width,height:844},isMobile:width<500,hasTouch:width<500});
  await page.goto('http://127.0.0.1:5211/tests/browser/mypage.html?pagination=1');
  const reviews=page.getByRole('navigation',{name:'리뷰 페이지'});
  await reviews.waitFor({timeout:5000});
  assert.equal(await page.locator('.list > li').count(),10);
  await page.evaluate(()=>window.qaFailReviewPage=2);
  await reviews.getByRole('button',{name:'3',exact:true}).click();
  await page.getByRole('button',{name:'다시 불러오기',exact:true}).click();
  await page.getByRole('link',{name:'예시 장소 21',exact:true}).waitFor();
  await reviews.getByRole('button',{name:'3',exact:true}).click();
  await page.getByRole('link',{name:'예시 장소 21',exact:true}).waitFor();
  assert.equal(await page.locator('.list > li').count(),1);
  await page.getByRole('button',{name:'예시 장소 21 리뷰 삭제',exact:true}).click();
  await page.getByRole('button',{name:'삭제할게요',exact:true}).click();
  await page.getByRole('button',{name:'네, 삭제할게요',exact:true}).click();
  await page.waitForFunction(()=>document.querySelector('[aria-label="리뷰 페이지"] [aria-current="page"]')?.textContent==='2');
  assert.equal(await page.locator('.list > li').count(),10);
  await page.getByLabel('리뷰 정렬 기준').selectOption('rating_desc');
  await page.waitForFunction(()=>document.querySelector('[aria-label="리뷰 페이지"] [aria-current="page"]')?.textContent==='1');
  await page.getByRole('navigation',{name:'마이페이지 메뉴'}).getByRole('link',{name:'찜한 장소'}).click();
  const favorites=page.getByRole('navigation',{name:'찜 페이지'});await favorites.waitFor();
  assert.equal(await page.locator('.cards > li').count(),6);
  await favorites.getByRole('button',{name:'3',exact:true}).click();
  assert.equal(await page.locator('.cards > li').count(),3);
  for(let i=0;i<3;i++) {
   const card=page.locator('.cards > li').first();
   await card.getByRole('button',{name:/메뉴/}).click();
   await card.getByRole('button',{name:/찜 해제/}).click();
  }
  await page.waitForFunction(()=>document.querySelector('[aria-label="찜 페이지"] [aria-current="page"]')?.textContent==='2');
  assert.equal(await page.locator('.cards > li').count(),6);
  await page.getByRole('button',{name:'장소명순',exact:true}).click();
  await page.waitForFunction(()=>document.querySelector('[aria-label="찜 페이지"] [aria-current="page"]')?.textContent==='1');
  assert.ok(await page.evaluate(w=>document.documentElement.scrollWidth<=w,width));
  await page.close();
 }
 console.log('Review/favorite pagination, deletion fallback, sort reset and desktop/mobile checks passed');
}finally{await browser.close()}})().catch(e=>{console.error(e);process.exit(1)});
