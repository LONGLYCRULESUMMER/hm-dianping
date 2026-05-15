const { chromium } = require('playwright');
const path = require('path');

(async () => {
  const browser = await chromium.launch();
  const context = await browser.newContext();
  const page = await context.newPage();

  const url = 'file://' + path.resolve(__dirname, 'slides.html');
  await page.goto(url, { waitUntil: 'networkidle' });

  await page.waitForFunction(() => {
    const sources = document.querySelectorAll('.mermaid');
    if (sources.length === 0) return true;
    return Array.from(sources).every(el => el.querySelector('svg'));
  }, { timeout: 60000 });

  await page.pdf({
    path: path.resolve(__dirname, 'login-module.pdf'),
    format: 'A4',
    landscape: true,
    printBackground: true,
    pageRanges: '1-11',
    margin: { top: 0, right: 0, bottom: 0, left: 0 }
  });

  await browser.close();
  console.log('PDF generated: login-module.pdf');
})();
