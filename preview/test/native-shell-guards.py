"""Offline fault injection against the APK's real assets and synchronous bridge contract."""
import json,os
from pathlib import Path
from playwright.sync_api import sync_playwright,expect
from urllib.parse import urlparse
root=Path(__file__).resolve().parents[2];assets=root/'app/src/main/assets/ai';out=root/'preview/.runtime'
fixture=json.loads((root/'preview/test/change-fixture.json').read_text(encoding='utf-8'))
with sync_playwright() as p:
    browser=p.chromium.launch(headless=True,executable_path=str(Path(os.environ['LOCALAPPDATA'])/'ms-playwright/chromium-1234/chrome-win64/chrome.exe'))
    page=browser.new_page(viewport={'width':392,'height':600});errors=[]
    page.on('pageerror',lambda e:errors.append(str(e)))
    def route(r):
        path=assets/urlparse(r.request.url).path.lstrip('/')
        r.fulfill(status=200,body=path.read_bytes(),content_type={'html':'text/html','css':'text/css','json':'application/json'}.get(path.suffix[1:],'text/javascript'))
    page.route('https://appassets.androidplatform.net/**',route)
    page.add_init_script('''
      const seed=SEED;window.b=JSON.parse(localStorage.getItem('test-board')||'null')||seed;window.rev=1;window.commits=0;window.failWrite=false;window.responses=[];window.delay=20;window.sentRequests=[];
      const ok=value=>JSON.stringify({ok:true,value});
      window.AndroidAI={snapshot:()=>ok({board:b,token:String(rev),date:'2026-09-26',viewedWeek:1,periods:Array.from({length:13},(_,i)=>({index:i+1,begin:'08:00',end:'08:45'}))}),
      commit:(token,next)=>{if(failWrite||token!==String(rev))return JSON.stringify({ok:false,error:'存储写入失败'});b=JSON.parse(next);rev++;commits++;localStorage.setItem('test-board',JSON.stringify(b));return ok(true);},
      loadSettings:()=>ok({config:{model:'deepseek-flash',contextTurns:30},apiKey:'mock-only'}),saveSettings:()=>ok(true),
      request:(id,body)=>{sentRequests.push(JSON.parse(body));const decision=responses.shift();setTimeout(()=>nativeReceive(id,{status:200,contentType:'application/json',chunk:btoa(unescape(encodeURIComponent(JSON.stringify({choices:[{finish_reason:'stop',message:{content:JSON.stringify(decision)}}]})))),done:true}),delay);},
      cancel:()=>{},copy:()=>{},action:()=>{}};
    '''.replace('SEED',json.dumps(fixture,ensure_ascii=False)))
    page.goto('https://appassets.androidplatform.net/index.html')
    expect(page.locator('#ai-cache-status')).not_to_have_text('正在读取本机记录')
    page.locator('[data-mode="timetable"]').click()
    action={'reply':'模型文字不作为执行结果','action':{'type':'add','name':'计算机基础','sessions':[{'day':6,'start':1,'end':2,'weeks':[1],'room':'礼东'}]}}
    def enqueue(response):page.evaluate('(r)=>responses.push(r)',response)
    def send(text='添加课程'):
        page.locator('#ai-input').fill(text);page.locator('#ai-send').click()
    def finish():expect(page.locator('#ai-send')).to_have_attribute('aria-label','发送消息',timeout=20000)
    before=page.evaluate('b')
    enqueue(action);page.evaluate('failWrite=true');send();finish()
    assert page.locator('.ai-message.assistant').last.locator('.has-error').count()==1 and page.evaluate('b')==before
    page.evaluate('failWrite=false;delay=500');enqueue(action);send();page.locator('#ai-send').click();finish();page.wait_for_timeout(600)
    assert page.evaluate('b')==before and page.evaluate('commits')==0
    enqueue(action);send();page.evaluate("b.courses[0].teacher='手动修改';rev++");finish()
    assert len(page.evaluate('b.courses'))==2 and page.evaluate('commits')==0
    print('PASS native shell guards: write failure, cancellation and stale snapshot never mutate data',flush=True)
    page.evaluate('(initial)=>{b=initial;rev++;delay=20;}',before);enqueue(action);send();finish()
    assert len(page.evaluate('b.courses'))==3
    page.reload();expect(page.locator('#ai-cache-status')).not_to_have_text('正在读取本机记录')
    page.locator('[data-ai="undo"]').click();assert page.evaluate('b.courses')==before['courses'] and page.evaluate('b.sessions')==before['sessions']
    print('PASS native shell guards: persisted receipt remains undoable after reload',flush=True)
    preview={'reply':'这里故意错误地说有11个课次','action':{'type':'import_preview','issues':[],'courses':[{'name':'新课','teacher':'','credit':'','note':'','sessions':[{'day':1,'start':1,'end':2,'weeks':[1],'room':'A'},{'day':3,'start':3,'end':4,'weeks':[1],'room':'B'}]}]}}
    enqueue(preview);enqueue(preview)
    page.locator('#ai-file').set_input_files(str(root/'app/src/test/resources/ai_vision.jpg'))
    expect(page.locator('.ai-attachment')).to_have_count(1);send('识别图片');finish()
    text=page.locator('.ai-message.assistant').last.inner_text();assert '1 门课程 · 2 个时间段' in text and '11个' not in text
    page.locator('[data-strategy="all"]').click();expect(page.locator('dialog')).to_be_visible();page.locator('#cancel-confirm').click()
    assert page.evaluate('b.courses')==before['courses']
    page.locator('[data-strategy="all"]').click();page.locator('#accept-confirm').click()
    expect(page.locator('[data-ai="import-confirm"]')).to_have_count(0)
    assert len(page.evaluate('b.courses'))==1 and len(page.evaluate('b.sessions'))==2
    page.locator('[data-ai="undo"]').click();assert page.evaluate('b.courses')==before['courses']
    print('PASS native shell guards: reviewed counts come from actual slots; import cancel, whole-board replace and undo',flush=True)
    page.evaluate("nativeTheme({surface:'#1c211a',surfaceSunken:'#242a20',textPrimary:'#e5ecde',primary:'#a5ce7e'})")
    assert page.locator('#ai-window').evaluate("e=>getComputedStyle(e).backgroundColor")=='rgb(28, 33, 26)'
    page.set_viewport_size({'width':340,'height':320});expect(page.locator('#ai-send')).to_be_visible()
    box=page.locator('#ai-send').bounding_box();assert box['y']+box['height']<=320
    page.screenshot(path=str(out/'test02-keyboard-dark.png'))
    assert not errors,errors
    print('PASS native shell guards: native theme tokens and compact keyboard viewport',flush=True)
    page.set_viewport_size({'width':392,'height':600})
    start_calls=page.evaluate('sentRequests.length');start_commits=page.evaluate('commits')
    week_action={'reply':'待处理','action':{'type':'add','name':'工科数分','sessions':[{'day':7,'start':1,'end':12,'weeks':None}]}}
    enqueue(week_action)
    week_action['action']['sessions'][0]['weeks']=list(range(1,17));enqueue(week_action)
    send('添加工科数分，星期天第1到12节，整个教学期每周都上。');finish()
    assert page.evaluate('sentRequests.length')==start_calls+2
    assert page.evaluate('commits')==start_commits+1
    assert not page.locator('.ai-message.assistant').last.locator('.has-error').count()
    course=next(c for c in page.evaluate('b.courses') if c['name']=='工科数分')
    slot=next(s for s in page.evaluate('b.sessions') if s['courseId']==course['id'])
    assert slot['weeks']==list(range(1,17)) and slot['day']==7 and slot['start']==1 and slot['end']==12
    page.locator('[data-ai="undo"]').click();assert page.evaluate('b.courses')==before['courses']
    print('PASS native shell guards: missing model weeks corrected before exactly one commit, then undo',flush=True)
    browser.close()
