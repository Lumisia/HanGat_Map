const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const os = require('node:os');
const path = require('node:path');
function luminance(rgb) {
  const c = rgb.slice(0, 3).map(v => v / 255).map(v => v <= .04045 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4);
  return c[0] * .2126 + c[1] * .7152 + c[2] * .0722;
}
(async () => {
  const browser = await chromium.launch({ headless: true });
  try {
    for (const width of [1440, 768, 575, 390, 320]) {
      const page = await browser.newPage({ viewport: { width, height: 1000 } });
      for (const result of [false, true]) {
        await page.goto(`http://127.0.0.1:5211/tests/browser/ai-builder.html${result ? '?preview=result' : ''}`);
        await page.locator(result ? '.result-shell' : '.stitch-builder').waitFor();
        for (const mode of ['dark', 'light', 'system-dark', 'system-light']) {
          const dark = mode.endsWith('dark');
          await page.emulateMedia({ colorScheme: dark ? 'dark' : 'light' });
          await page.evaluate(mode => mode.startsWith('system') ? document.documentElement.removeAttribute('data-theme') : document.documentElement.setAttribute('data-theme', mode), mode);
          await page.evaluate(async () => { await new Promise(requestAnimationFrame); await Promise.all(document.getAnimations().map(a => a.finished.catch(() => {}))); });
          const selectors = result ? ['.ai-course-page', '.itinerary-card', '.course-item', '.day-tabs', '.course-summary-card', '.weather-group .temperature', '.weather-group .rain'] : ['.ai-course-page', '.condition-main', '.condition-main input', '.condition-summary dl'];
          for (const selector of selectors) {
            const colors = await page.locator(selector).first().evaluate(el => {
              const s = getComputedStyle(el);
              return { bg: s.backgroundColor, fg: s.color };
            });
            const rgb = colors.bg.match(/[\d.]+/g).map(Number);
            assert.equal(rgb.slice(0, 3).reduce((a, b) => a + b) / 3 < 128, dark, `${width} ${mode} ${selector}: ${JSON.stringify(colors)}`);
            const fg = colors.fg.match(/[\d.]+/g).map(Number);
            const a = luminance(rgb), b = luminance(fg);
            assert.ok((Math.max(a, b) + .05) / (Math.min(a, b) + .05) >= 4.5, `${mode} low text contrast ${selector}`);
          }
          assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
          if (!mode.startsWith('system')) await page.screenshot({ path: path.join(os.tmpdir(), `ai-${result ? 'result' : 'builder'}-${mode}-${width}.png`), fullPage: true });
          if (!result) {
            for (const [step, selector] of [[2, '.region-cards button'], [3, '[data-error-target="styles"] button'], [4, '.preference-sections input']]) {
              await page.locator('.builder-progress nav button').nth(step - 1).click();
              await page.locator(selector).first().waitFor();
              if (step === 2 || step === 3) {
                const groups = step === 2 ? ['.region-cards'] : ['.course-radio', '[data-error-target="styles"]'];
                for (const group of groups) {
                  const alignment = await page.locator(group).evaluate(el => {
                    const section = el.closest('.condition-section');
                    const heading = section.querySelector('.section-title');
                    const rect = el.getBoundingClientRect(), title = heading.getBoundingClientRect();
                    return { left: rect.left - title.left, right: rect.right - title.right };
                  });
                  assert.ok(Math.abs(alignment.left) <= 1 && Math.abs(alignment.right) <= 1, `${width} ${mode} ${group} must align with section: ${JSON.stringify(alignment)}`);
                }
              }
              await page.evaluate(async () => { await Promise.all(document.getAnimations().map(a => a.finished.catch(() => {}))); });
              const bg = await page.locator(selector).first().evaluate(el => getComputedStyle(el).backgroundColor);
              assert.equal(bg.match(/[\d.]+/g).slice(0, 3).map(Number).reduce((a, b) => a + b) / 3 < 128, dark, `${mode} step ${step}: ${bg}`);
              if (mode === 'dark' && width === 575 && step < 4) await page.screenshot({ path: path.join(os.tmpdir(), `ai-step-${step}-dark-${width}.png`), fullPage: true });
            }
            await page.locator('.builder-progress nav button').first().click();
          }
        }
      }
      await page.close();
      console.log(`AI themes ${width}px PASS`);
    }
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exit(1); });
