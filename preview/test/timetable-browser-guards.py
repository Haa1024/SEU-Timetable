"""Browser failure-path checks with controlled model replies; no paid API calls."""
import json
import os
from pathlib import Path
from playwright.sync_api import sync_playwright, expect

out = Path('preview/.runtime')
with sync_playwright() as p:
    browser = p.chromium.launch(headless=True, executable_path=str(Path(os.environ['LOCALAPPDATA'])/'ms-playwright/chromium-1234/chrome-win64/chrome.exe'))
    page = browser.new_page(viewport={'width':1440,'height':1080})
    errors, checks, pending = [], [], []
    page.on('pageerror', lambda e: errors.append(str(e)))
    page.goto('http://127.0.0.1:4173')
    expect(page.locator('#connection')).to_have_text('本地服务已连接')
    fixture=page.request.get('http://127.0.0.1:4173/api/source').json()['board']
    page.evaluate("""() => {
      sessionStorage.setItem('seu-llm-api-key','mock-key');
      const s=JSON.parse(localStorage.getItem('seu-browser-preview-v1'));s.page=s.tab='timetable';localStorage.setItem('seu-browser-preview-v1',JSON.stringify(s));
    }""")
    page.reload();page.locator('#ai-bubble').click();page.locator('[data-mode="timetable"]').click()
    expect(page.locator('[data-mode="timetable"]')).to_have_attribute('aria-pressed','true')
    page.route('**/api/llm/chat', lambda r: pending.append(r))
    action={'type':'add','name':'边界测试课程','sessions':[{'day':6,'start':1,'end':2,'weeks':[1],'room':''}]}
    compound={'type':'batch','actions':[{'type':'delete','target':{'name':fixture['courses'][0]['name']},'scope':'course','weeks':None},action]}
    def data(): return page.evaluate("JSON.parse(localStorage.getItem('seu-browser-preview-v1')).boards")
    def begin():
        page.locator('#ai-input').fill('第1周周六第1、2节添加边界测试课程')
        page.locator('#ai-send').click()
        expect(page.locator('#ai-send')).to_have_attribute('aria-label','停止生成')
        expect(page.locator('[data-mode="chat"]')).to_be_disabled()
        for _ in range(100):
            if pending: return pending.pop(0)
            page.wait_for_timeout(20)
        raise AssertionError('No request')
    def finish(route, payload, reason='stop'):
        route.fulfill(content_type='application/json',body=json.dumps({'choices':[{'finish_reason':reason,'message':{'content':json.dumps(payload,ensure_ascii=False)}}]}))
        expect(page.locator('#ai-send')).to_have_attribute('aria-label','发送消息')
    def failed(name, pattern, before):
        expect(page.locator('.ai-message.assistant').last.locator('.has-error')).to_contain_text(pattern)
        assert data()==before
        checks.append(name);print('PASS '+name,flush=True)

    for name,payload,reason,hint in [
      ('truncated response cannot write',{'reply':'添加','action':action},'length','不完整'),
      ('unknown action cannot write',{'reply':'修改','action':{'type':'replace'}},'stop','不支持'),
      ('absent weeks rejected by executor',{'reply':'添加','action':{**action,'sessions':[{**action['sessions'][0],'weeks':None}]}},'stop','周次'),
      ('absent time rejected by executor',{'reply':'添加','action':{**action,'sessions':[{'weeks':[1]}]}},'stop','星期'),
      ('invalid second step rolls back first delete',{'reply':'组合','action':{'type':'batch','actions':[compound['actions'][0],{**action,'sessions':[{'day':6,'start':1,'end':99,'weeks':[1]}]}]}},'stop','结束节'),
    ]:
        before=data();route=begin();finish(route,payload,reason);failed(name,hint,before)

    before=data();route=begin()
    page.evaluate("""() => { window.originalSetItem=Storage.prototype.setItem;Storage.prototype.setItem=function(k,v){if(this===localStorage&&k==='seu-browser-preview-v1')throw new DOMException('Quota exceeded','QuotaExceededError');return window.originalSetItem.call(this,k,v);}; }""")
    finish(route,{'reply':'组合','action':compound})
    failed('storage failure does not commit any batch step','存储写入失败',before)
    page.evaluate('() => { Storage.prototype.setItem=window.originalSetItem; }')

    route=begin()
    page.evaluate("""() => { const k='seu-browser-preview-v1';const s=JSON.parse(localStorage.getItem(k));s.externalEdit='another-tab';localStorage.setItem(k,JSON.stringify(s)); }""")
    before=data();finish(route,{'reply':'添加','action':action});failed('another tab invalidates pending action','其他页面',before)
    assert page.evaluate("JSON.parse(localStorage.getItem('seu-browser-preview-v1')).externalEdit")=='another-tab'
    page.reload();page.locator('#ai-bubble').click()

    before=data();route=begin();page.locator('#ai-send').click()
    expect(page.locator('#ai-send')).to_have_attribute('aria-label','发送消息')
    expect(page.locator('.ai-message.assistant').last).to_contain_text('已停止')
    try: finish(route,{'reply':'组合','action':compound})
    except Exception: pass
    assert data()==before
    checks.append('stopped request cannot execute delayed action');print('PASS '+checks[-1],flush=True)

    # Drafts are independent, and a new board receives a fresh operations conversation.
    page.locator('#ai-input').fill('课表模式草稿')
    page.locator('[data-mode="chat"]').click();expect(page.locator('#ai-input')).to_have_value('')
    page.locator('#ai-input').fill('普通聊天草稿')
    page.locator('[data-mode="timetable"]').click();expect(page.locator('#ai-input')).to_have_value('课表模式草稿')
    page.wait_for_timeout(400)
    page.reload();page.locator('#ai-bubble').click();expect(page.locator('#ai-input')).to_have_value('课表模式草稿')
    page.locator('[data-mode="chat"]').click();expect(page.locator('#ai-input')).to_have_value('普通聊天草稿')
    page.locator('[data-mode="timetable"]').click()
    page.locator('[data-ai="minimize"]').click()
    page.locator('[data-action="boards"]').click();page.locator('[data-action="new-board"]').first.click()
    page.locator('[name="boardName"]').fill('另一张课表')
    page.locator('[name="firstMonday"]').fill('2026-09-21');page.locator('[data-action="create-board"]').click()
    page.locator('#ai-bubble').click();expect(page.locator('#ai-board-label')).to_contain_text('另一张课表')
    expect(page.locator('.ai-message')).to_have_count(0)
    page.reload();page.locator('#ai-bubble').click();expect(page.locator('.ai-message')).to_have_count(0)
    expect(page.locator('#ai-input')).to_have_value('')
    checks.append('mode drafts and board histories are isolated');print('PASS '+checks[-1],flush=True)
    # Narrowest supported preview remains usable with two mode controls.
    page.select_option('#device','360,800')
    expect(page.locator('#ai-composer')).to_be_visible()
    rect=page.locator('#ai-window').bounding_box(); phone=page.locator('#phone').bounding_box()
    assert rect['x']>=phone['x'] and rect['x']+rect['width']<=phone['x']+phone['width']+1
    assert page.locator('#ai-window').evaluate('(el)=>el.scrollWidth<=el.clientWidth+1')
    page.locator('#phone').screenshot(path=str(out/'timetable-ai-small.png'))
    # Even if a model misses ambiguity, the executor asks without committing.
    import copy
    ambiguous=copy.deepcopy(fixture);ambiguous['id']='ambiguous';ambiguous['name']='同名课程验收'
    original=ambiguous['courses'][0];original['teacher']='甲老师'
    second={**original,'id':'same-name-second','teacher':'乙老师'}
    ambiguous['courses'].append(second)
    ambiguous['sessions'].append({**ambiguous['sessions'][0],'id':'same-time-second','courseId':second['id']})
    # Seed before app bootstrap rather than racing a pending UI render's save.
    page.add_init_script("const guardBoard="+json.dumps(ambiguous,ensure_ascii=False)+";{const k='seu-browser-preview-v1',s=JSON.parse(localStorage.getItem(k));s.boards[guardBoard.id]=guardBoard;s.activeId=guardBoard.id;localStorage.setItem(k,JSON.stringify(s));}")
    page.reload();expect(page.locator('#connection')).to_have_text('本地服务已连接');page.locator('#ai-bubble').click()
    expect(page.locator('#ai-board-label')).to_contain_text('同名课程验收')
    before=data();route=begin()
    assert route.request.post_data_json['context']['board']['id']=='ambiguous'
    finish(route,{'reply':'换课','action':{'type':'change','target':{'name':original['name']},'scope':'course','weeks':None,'to':{'name':'新课程'}}})
    answer=page.locator('.ai-message.assistant').last
    expect(answer).to_contain_text('甲老师');expect(answer).to_contain_text('乙老师');expect(answer).to_contain_text('课表未修改')
    assert answer.locator('.has-error').count()==0 and data()==before
    checks.append('missed model ambiguity becomes a persisted clarification');print('PASS '+checks[-1],flush=True)
    assert not errors,errors
    (out/'timetable-ai-guards-result.json').write_text(json.dumps({'checks':checks,'browserErrors':errors},ensure_ascii=False,indent=2),encoding='utf-8')
    browser.close()
