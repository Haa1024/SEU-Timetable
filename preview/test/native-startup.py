"""APK UI startup regression checks. Desktop Chromium is NOT an Android WebView test."""
import json, os
from pathlib import Path
from urllib.parse import urlparse
from playwright.sync_api import sync_playwright, expect

root = Path(__file__).resolve().parents[2]
assets = root / 'app/src/main/assets/ai'
out = root / 'preview/.runtime'
out.mkdir(exist_ok=True)
with sync_playwright() as p:
    browser = p.chromium.launch(headless=True, executable_path=str(Path(os.environ['LOCALAPPDATA']) / 'ms-playwright/chromium-1234/chrome-win64/chrome.exe'))
    for case in ['chat', 'settings', 'missing-module', 'syntax-error', 'bridge-error']:
        page = browser.new_page(viewport={'width': 360, 'height': 620})
        failure = {'active': True}
        def route(r):
            name = urlparse(r.request.url).path.lstrip('/')
            if failure['active'] and name == 'ai-chat.js':
                if case == 'missing-module':
                    r.fulfill(status=403, body='Blocked'); return
                if case == 'syntax-error':
                    r.fulfill(status=200, body='export const broken = ;', content_type='text/javascript'); return
            file = assets / name
            r.fulfill(status=200, body=file.read_bytes(), content_type={'html': 'text/html', 'css': 'text/css', 'json': 'application/json'}.get(file.suffix[1:], 'text/javascript'))
        page.route('https://appassets.androidplatform.net/**', route)
        page.add_init_script('''
            window.actions=[];
            const ok=value=>JSON.stringify({ok:true,value});
            window.AndroidAI={
                loadSettings:()=>{
                    if(CASE==='bridge-error' && !sessionStorage.getItem('retry')) throw new Error('test failure');
                    return ok({config:{model:'deepseek-flash'},apiKey:''});
                },
                saveSettings:()=>ok(true), snapshot:()=>JSON.stringify({ok:false,error:'no board'}),
                action:value=>{actions.push(value);if(value==='ready')nativeShow(CASE==='settings');},
                copy:()=>{},request:()=>{throw new Error('No network expected');},cancel:()=>{}
            };
        '''.replace('CASE', json.dumps(case)))
        page.goto('https://appassets.androidplatform.net/index.html')
        if case in ['missing-module', 'syntax-error', 'bridge-error']:
            page.wait_for_function("() => actions.includes('startup-error')")
            assert not page.evaluate("actions.includes('ready')")
            expect(page.locator('#boot')).to_contain_text('启动失败')
            failure['active'] = False
            page.evaluate("sessionStorage.setItem('retry','1')")
            page.reload()
        page.wait_for_function("() => actions.includes('ready')")
        expect(page.locator('#boot')).to_have_count(0)
        expect(page.locator('#llm-settings-form' if case == 'settings' else '#ai-window')).to_be_visible()
        for _ in range(3):
            page.evaluate('nativeShow(true)')
            expect(page.locator('#llm-settings-form')).to_be_visible()
            page.evaluate('nativeShow(false)')
            expect(page.locator('#ai-window')).to_be_visible()
        page.screenshot(path=str(out / ('test02-startup-' + case + '.png')))
        print('PASS startup: ' + case + ', retry and chat/settings switching', flush=True)
        page.close()
    browser.close()
