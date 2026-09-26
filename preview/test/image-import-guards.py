"""Fault injection for the browser's final image-import confirmation boundary."""
import json,os
from pathlib import Path
from playwright.sync_api import sync_playwright,expect
fixture=json.loads(Path('preview/test/change-fixture.json').read_text(encoding='utf-8'))
results=[]
with sync_playwright() as p:
    browser=p.chromium.launch(headless=True,executable_path=str(Path(os.environ['LOCALAPPDATA'])/'ms-playwright/chromium-1234/chrome-win64/chrome.exe'))
    for case in ['cancel','storage','stale']:
        context=browser.new_context();page=context.new_page()
        page.goto('http://127.0.0.1:4173/api/health')
        page.evaluate('''async b=>{
          const source=await (await fetch('/api/source')).json();const ai=await import('/timetable-ai.js');const store=await import('/ai-storage.js');
          localStorage.setItem('seu-browser-preview-v1',JSON.stringify({page:'timetable',tab:'timetable',date:'2026-09-26',week:1,activeId:b.id,boards:{[b.id]:b}}));
          localStorage.setItem('seu-ai-mode',JSON.stringify('timetable'));
          const draft={type:'import_preview',issues:[],courses:[{name:'新课程',teacher:'',credit:'',note:'',sessions:[{day:6,start:1,end:2,weeks:[1],room:'礼东'}]}]};
          await store.saveChat({phase2:{messages:[{id:'review',role:'assistant',text:'已识别新课程，尚未导入',status:'complete',createdAt:Date.now()}],draft:{text:'',images:[]},importDraft:{id:'proposal',messageId:'review',draft,context:ai.operationContext(b,source.periods,'2026-09-26',1)}}},'timetable-conversations');
        }''',fixture)
        page.goto('http://127.0.0.1:4173');expect(page.locator('#connection')).to_have_text('本地服务已连接');page.locator('#ai-bubble').click()
        before=page.evaluate("localStorage.getItem('seu-browser-preview-v1')")
        page.locator('[data-strategy="all"]').click();expect(page.locator('#modal-root dialog')).to_be_visible()
        assert page.evaluate("localStorage.getItem('seu-browser-preview-v1')")==before
        if case=='cancel':
            page.get_by_role('button',name='返回核对',exact=True).click()
            assert page.evaluate("localStorage.getItem('seu-browser-preview-v1')")==before
        else:
            if case=='storage':
                page.evaluate("""()=>{const set=Storage.prototype.setItem;Storage.prototype.setItem=function(k,v){if(k==='seu-browser-preview-v1')throw new DOMException('Quota','QuotaExceededError');return set.call(this,k,v);};}""")
            else:
                page.evaluate("""()=>{const k='seu-browser-preview-v1',s=JSON.parse(localStorage.getItem(k));s.boards.phase2.courses[0].note='other tab';localStorage.setItem(k,JSON.stringify(s));}""")
                before=page.evaluate("localStorage.getItem('seu-browser-preview-v1')")
            page.get_by_role('button',name='确认导入',exact=True).click();expect(page.locator('#ai-error')).to_be_visible()
            assert page.evaluate("localStorage.getItem('seu-browser-preview-v1')")==before
        expect(page.locator('[data-strategy="all"]')).to_be_visible()
        results.append({'case':case,'noMutation':True});print('PASS import confirmation '+case,flush=True)
        context.close()
    browser.close()
Path('preview/.runtime/image-import-guards.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
