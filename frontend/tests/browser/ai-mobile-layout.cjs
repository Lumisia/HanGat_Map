const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');

(async () => {
  const browser = await chromium.launch({ headless: true });
  const failures = [];
  try {
    for (const width of [360, 390, 767]) {
      for (const theme of ['light', 'dark']) {
        const page = await browser.newPage({ viewport: { width, height: 850 }, isMobile: true, hasTouch: true });
        await page.goto('http://127.0.0.1:5211/tests/browser/ai-builder.html?nav');
        await page.locator('.builder-progress nav button').first().waitFor();
        await page.evaluate(t => document.documentElement.dataset.theme = t, theme);
        await page.locator('.builder-progress nav button').last().click();
        const gap = await page.evaluate(() => {
          const note = document.querySelector('.ticket-note').getBoundingClientRect();
          const summary = document.querySelector('.condition-summary').getBoundingClientRect();
          return summary.bottom - note.bottom;
        });
        if (gap > 32) failures.push(`${width}/${theme}: unnecessary space below note: ${gap}px`);
        for (const days of [4, 7]) {
          await page.goto(`http://127.0.0.1:5211/tests/browser/ai-builder.html?nav&preview=result&days=${days}`);
          await page.locator('.day-tabs button').last().waitFor();
          await page.evaluate(t => document.documentElement.dataset.theme = t, theme);
          const size = await page.evaluate(() => {
            const card = document.querySelector('.itinerary-card').getBoundingClientRect();
            const tabs = document.querySelector('.day-tabs');
            tabs.scrollLeft = tabs.scrollWidth;
            return { right: card.right, viewport: innerWidth, scroll: tabs.scrollLeft };
          });
          if (size.right > width + 1) failures.push(`${width}/${theme}/${days} days: card extends to ${size.right}`);
          if (width < 400 && size.scroll <= 0) failures.push(`${width}/${theme}/${days} days: tabs cannot scroll`);
          if (size.right > width + 1) continue;
          await page.locator('.day-tabs button').last().click();
          assert.equal(await page.locator('.day-tabs button').last().getAttribute('aria-selected'), 'true');
          assert.match(await page.locator('.day-title').innerText(), new RegExp(`${days}일차`));
          if (width === 390 && days === 7) {
            await page.screenshot({ path: require('node:path').join(require('node:os').tmpdir(), `ai-mobile-layout-${theme}.png`), fullPage: true });
          }
        }
        await page.close();
      }
    }
    assert.deepEqual(failures, []);
    console.log('Mobile spacing and 4/7-day tabs PASS (360/390/767px, light/dark)');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exit(1); });
