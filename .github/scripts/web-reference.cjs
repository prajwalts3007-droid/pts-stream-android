// Public web assets only. Every API request is fulfilled from synthetic fixtures;
// no login, production mutation, real media URL or private library export is used.
const { chromium } = require('playwright');
const fs = require('node:fs');
const assert = require('node:assert/strict');
const origin = 'https://ptsmovies.online';
const output = 'web-reference';
fs.mkdirSync(output, { recursive: true });
const user = { id: 90011, username: 'synthetic', name: 'Alex', role: 'admin', is_active: true };
const jobs = [
  { job_id: 'fixture-queued', title: 'The Open Road', year: 2025, type: 'series', season: 1, episode: 1, stage: 'queued', progress: 0, download_url: 'https://example.invalid/fixture.mp4' },
  { job_id: 'fixture-failed', title: 'Generated failed job', year: 2026, type: 'movie', stage: 'failed', progress: 0, error: 'Synthetic source failure', download_url: 'https://example.invalid/fixture.mp4' },
];
const title = { id: 90001, title: 'The Open Road', kind: 'series', year: 2025, synopsis: 'Synthetic audit fixture. No third-party media.', video_codec: 'h264', container: 'mp4', genres: ['Adventure'], episodes: [{ id: 101, season: 1, episode: 1, title: 'A new beginning', video_codec: 'h264', duration: 30, container: 'mp4' }] };
const media = fs.readFileSync('/tmp/pts-web-fixture.mp4');
const calls = [], errors = [], geometry = [], blockedNetwork = [];
function fixture(path, request) {
  if (path === '/auth/config') return { google_enabled: false, origin };
  if (path === '/auth/csrf') return { csrf_token: 'synthetic-only' };
  if (path === '/auth/me') return { user };
  if (path === '/queue') return { jobs, completed: [] };
  if (path.endsWith('/retry') || path === '/queue/retry-all') return { retried: 1, job: { stage: 'queued' } };
  if (path.endsWith('/edit')) return { job: { stage: 'queued' } };
  if (path === '/stream/library') return { titles: [title], watch: [], progress: [] };
  if (path === '/stream/prefs') return { autoplay: true, speed: 1, zoom_mode: 'reset' };
  if (path === '/admin/users') return { users: [user] };
  if (path === '/admin/files') return { entries: [{ name: 'Movies', is_dir: true, size: 0 }, { name: 'notes.txt', is_dir: false, size: 20 }] };
  if (path === '/admin/files/ops') return { ops: [] };
  if (path === '/relay') return { jobs: [] };
  if (path === '/admin/github') return { configured: true, max_runners: 4, per_account: 20, accounts: [{ role: 'primary', active: 0, repo: 'generated/fixture', token_set: false }] };
  if (path === '/admin/google') return { enabled: false, origin, client_id: '' };
  if (path === '/admin/pipeline-settings') return { pi_bwlimit_kbps: 12345, max_runners: 4, max_pi_transfers: 1, min_free_gib: 10, max_staging_gib: 0, reserve_per_job_gib: 6, status: { active_runners: 0, active_transfers: 0, waiting_transfers: 0 } };
  if (path === '/system/metrics') return { vps: { online: true, hostname: 'synthetic', cpu_percent: 34, mem_percent: 58, disk_percent: 71 }, pi: { online: true } };
  if (path === '/imdb/search') return { results: [] };
  return {};
}
(async () => {
  const browser = await chromium.launch();
  try {
    const context = await browser.newContext({ viewport: { width: 390, height: 844 }, serviceWorkers: 'block' });
    await context.route('**/*', async route => {
      const request = route.request(), url = new URL(request.url());
      if (url.pathname.startsWith('/api/')) {
        const path = url.pathname.replace('/api/v1', '');
        calls.push({ path, method: request.method(), body: request.postData() });
        if (path === '/stream/file/episode/101') {
          const range = /^bytes=(\d+)-(\d*)$/.exec(request.headers().range || '');
          const start = range ? Number(range[1]) : 0;
          const end = range && range[2] ? Math.min(Number(range[2]), media.length - 1) : media.length - 1;
          return route.fulfill({ status: range ? 206 : 200, contentType: 'video/mp4',
            headers: { 'Accept-Ranges': 'bytes', ...(range ? { 'Content-Range': `bytes ${start}-${end}/${media.length}` } : {}) }, body: media.subarray(start, end + 1) });
        }
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(fixture(path, request)) });
      }
      if (!['GET', 'HEAD'].includes(request.method())) {
        blockedNetwork.push({ host: url.hostname, path: url.pathname, method: request.method() });
        return route.abort(); // Includes analytics beacons; never permit a real network write.
      }
      if (url.hostname === 'media.ptsmovies.online') return route.abort();
      return route.continue();
    });
    const page = await context.newPage();
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(origin, { waitUntil: 'domcontentloaded' });
    await page.waitForFunction(() => typeof switchPage === 'function' && document.querySelector('#app-view') && !document.querySelector('#app-view').hidden);
    await page.evaluate(() => { document.documentElement.dataset.theme = 'dark'; return document.fonts.ready; });
    for (const [width, height] of [[320, 740], [390, 844], [412, 915], [740, 390]]) {
      await page.setViewportSize({ width, height });
      for (const name of ['dashboard', 'queue', 'files', 'transfers', 'download', 'settings', 'stream-home', 'stream-series']) {
        await page.evaluate(name => switchPage(name), name);
        await page.locator('#page-' + name).waitFor({ state: 'visible' });
        if (name === 'queue') await page.locator('#queue-list .q-title').first().waitFor({ state: 'visible' });
        if (name === 'settings') await page.locator('#pipeline-runners').waitFor({ state: 'visible' });
        await page.screenshot({ path: `${output}/${width}x${height}-${name}.png`, fullPage: true });
      }
      await page.evaluate(() => switchPage('queue'));
      const boxes = await page.locator('.q-stage-tab').evaluateAll(nodes => nodes.map(n => { const r = n.getBoundingClientRect(); return { label: n.getAttribute('aria-label'), left: r.left, right: r.right, top: r.top }; }));
      assert.equal(boxes.length, 9);
      assert.ok(boxes.every(b => Math.abs(b.top - boxes[0].top) < 2 && b.left >= 0 && b.right <= width + 1));
      geometry.push({ width, height, queue: boxes });
      await page.evaluate(() => stOpenWatch(90001, 101));
      await page.locator('#st-player').waitFor({ state: 'visible' });
      await page.waitForFunction(() => document.querySelector('#st-video').readyState >= 2);
      await page.evaluate(() => { const video = document.querySelector('#st-video'); video.loop = true; video.muted = true; return video.play(); });
      await page.waitForFunction(() => document.querySelector('#st-video').currentTime > 0);
      await page.evaluate(() => plShowControls());
      await page.screenshot({ path: `${output}/${width}x${height}-player-playing.png`, fullPage: true });
      const regions = await page.evaluate(() => ['.st-player-top', '.st-player-bottom', '#st-player'].map(selector => {
        const r = document.querySelector(selector).getBoundingClientRect(); return { selector, x: r.x, y: r.y, width: r.width, height: r.height };
      }));
      geometry.push({ width, height, player: regions });
      await page.evaluate(() => plToggleControls());
      assert.ok(await page.locator('#st-player').evaluate(n => n.classList.contains('controls-off')));
      await page.evaluate(() => plToggleControls());
      await page.waitForFunction(() => document.querySelector('#st-player').classList.contains('controls-off'), null, { timeout: 6500 });
      await page.screenshot({ path: `${output}/${width}x${height}-player-autohidden.png`, fullPage: true });
      await page.evaluate(() => { document.querySelector('#st-video').pause(); plShowControls(); });
      await page.screenshot({ path: `${output}/${width}x${height}-player-paused.png`, fullPage: true });
      await page.evaluate(() => plToggleControls());
      assert.ok(await page.locator('#st-player').evaluate(n => n.classList.contains('controls-off')));
      await page.evaluate(() => plToggleControls());
      assert.ok(await page.locator('#st-player').evaluate(n => !n.classList.contains('controls-off')));
    }
    await page.setViewportSize({ width: 390, height: 844 });
    await page.evaluate(() => switchPage('queue'));
    await page.locator('#queue-list .q-title').first().waitFor({ state: 'visible' });
    await page.locator('[data-q-stage="failed"]').click();
    const retried = page.waitForResponse(response => new URL(response.url()).pathname === '/api/v1/queue/fixture-failed/retry' && response.request().method() === 'POST');
    await page.locator('[data-retry="fixture-failed"]').click();
    await retried; // Web per-job Retry is immediate; native intentionally adds confirmation.
    await page.locator('[data-edit="fixture-failed"]').click();
    await page.locator('#dlg-input').fill('https://example.invalid/replacement.mp4');
    await page.screenshot({ path: `${output}/390x844-queue-edit-dialog.png`, fullPage: true });
    const edited = page.waitForResponse(response => new URL(response.url()).pathname === '/api/v1/queue/fixture-failed/edit' && response.request().method() === 'POST');
    await page.locator('#dlg-ok').click();
    await edited;
    await page.locator('#q-retry-all').click();
    const retriedAll = page.waitForResponse(response => new URL(response.url()).pathname === '/api/v1/queue/retry-all' && response.request().method() === 'POST');
    await page.locator('#dlg-ok').click();
    await retriedAll;
    await page.waitForFunction(() => document.querySelector('#dialog').hidden);
    assert.ok(calls.some(c => c.path === '/queue/fixture-failed/retry' && c.method === 'POST'));
    assert.ok(calls.some(c => c.path === '/queue/fixture-failed/edit' && JSON.parse(c.body).download_url === 'https://example.invalid/replacement.mp4'));
    assert.ok(calls.some(c => c.path === '/queue/retry-all' && c.method === 'POST'));
    fs.writeFileSync(`${output}/report.json`, JSON.stringify({ geometry, mockedCalls: calls, browserErrors: errors, blockedNetwork }, null, 2));
    assert.equal(errors.length, 0, 'Deployed web failed against synthetic fixture APIs');
  } finally { await browser.close(); }
})().catch(error => { fs.writeFileSync(`${output}/failure.txt`, error.stack); fs.writeFileSync(`${output}/report.json`, JSON.stringify({ geometry, mockedCalls: calls, browserErrors: errors, blockedNetwork }, null, 2)); process.exitCode = 1; });
