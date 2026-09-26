"""Real API / browser phase-two acceptance in an isolated browser context.
python preview/test/timetable-change-browser.py --key-file <local file>
"""
import argparse
import json
import os
from pathlib import Path
from playwright.sync_api import sync_playwright, expect

parser=argparse.ArgumentParser();parser.add_argument('--key-file',required=True);args=parser.parse_args()
out=Path('preview/.runtime');out.mkdir(exist_ok=True)
fixture=json.loads(Path('preview/test/change-fixture.json').read_text(encoding='utf-8'))
key=Path(args.key_file).read_text(encoding='utf-8-sig').strip()
with sync_playwright() as p:
    browser=p.chromium.launch(headless=True,executable_path=str(Path(os.environ['LOCALAPPDATA'])/'ms-playwright/chromium-1234/chrome-win64/chrome.exe'))
    page=browser.new_page(viewport={'width':1440,'height':1080})
    errors=[];results=[];payloads=[]
    page.on('pageerror',lambda e:errors.append(str(e)))
    page.goto('http://127.0.0.1:4173');expect(page.locator('#connection')).to_have_text('本地服务已连接')
    page.evaluate("""([board,key])=>{
      const k='seu-browser-preview-v1',s=JSON.parse(localStorage.getItem(k));s.page=s.tab='timetable';s.date='2026-09-24';s.week=1;s.boards[board.id]=board;s.activeId=board.id;localStorage.setItem(k,JSON.stringify(s));
      sessionStorage.setItem('seu-llm-api-key',key);localStorage.setItem('seu-llm-config-v1',JSON.stringify({model:'deepseek-flash',maxTokens:2048}));
    }""",[fixture,key])
    page.reload();expect(page.locator('#connection')).to_have_text('本地服务已连接');page.locator('#ai-bubble').click();page.locator('[data-mode="timetable"]').click()
    expect(page.locator('[data-mode="timetable"]')).to_have_attribute('aria-pressed','true')
    def capture(response):
        if '/api/llm/chat' in response.url:
            try:
                data=response.json();payloads.append(data['choices'][0]['message']['content'])
                (out/'timetable-change-model-responses.json').write_text(json.dumps(payloads,ensure_ascii=False,indent=2),encoding='utf-8')
            except Exception:pass
    page.on('response',capture)
    def board():return page.evaluate("JSON.parse(localStorage.getItem('seu-browser-preview-v1')).boards.phase2")
    def blocks(course):return [s for s in board()['sessions'] if s['courseId']==course]
    def ask(message):
        count=page.locator('.ai-message.assistant').count();page.locator('#ai-input').fill(message);page.locator('#ai-send').click()
        expect(page.locator('.ai-message.assistant')).to_have_count(count+1)
        expect(page.locator('#ai-send')).to_have_attribute('aria-label','发送消息',timeout=180000)
        answer=page.locator('.ai-message.assistant').last
        assert answer.locator('.has-error').count()==0,answer.inner_text()
        reply=answer.locator('.ai-markdown').inner_text();results.append({'input':message,'reply':reply})
        (out/'timetable-change-progress.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
        return reply
    def passed(name):print('PASS '+name,flush=True)

    ask('把今天的英语课调整到本周2 23节')
    moved=next(s for s in blocks('english') if s['day']==2)
    assert moved['start']==2 and moved['end']==3 and moved['weeks']==[1] and moved['room']=='礼东'
    assert next(s for s in blocks('english') if s['day']==4)['weeks']==[2,3,4]
    assert next(c for c in board()['courses'] if c['id']=='english')==fixture['courses'][0]
    expect(page.locator('.course-block[data-id="english"]').filter(has=page.locator('.course-name',has_text='英语'))).to_have_count(2)
    passed('today moves to earlier Tuesday in same week and retains metadata')
    page.locator('#phone').screenshot(path=str(out/'timetable-change-move.png'))

    ask('明天的英语课改成cpp课')
    cpp=next(c for c in board()['courses'] if c['name'].lower().replace('课','')=='cpp');cppid=cpp['id']
    assert cpp['teacher']=='' and cpp['credit']=='' and cpp['note']=='' and cpp['colorOverride'] is None
    assert [(s['day'],s['start'],s['end'],s['weeks'],s['room']) for s in blocks(cppid)]==[(5,3,4,[1],'教三')]
    assert next(s for s in blocks('english') if s['day']==5)['weeks']==[2,3,4]
    passed('tomorrow replacement keeps future English and creates only one CPP occurrence')

    ask('cpp老师设置为是王鑫')
    cpp=next(c for c in board()['courses'] if c['id']==cppid)
    assert cpp['teacher']=='王鑫' and cpp['credit']=='' and cpp['note']==''
    passed('replacement course accepts optional follow-up without duplicate creation')

    ask('cpp课在中山院上课')
    assert all(s['room']=='中山院' for s in blocks(cppid))
    assert next(c for c in board()['courses'] if c['id']==cppid)==cpp
    passed('room follow-up succeeds independently and preserves the already saved teacher')

    ask('刚换成的那门课改到下周一上午头两节，教室换成礼西')
    assert [(s['day'],s['start'],s['end'],s['weeks'],s['room']) for s in blocks(cppid)]==[(1,1,2,[2],'礼西')]
    assert next(c for c in board()['courses'] if c['id']==cppid)==cpp
    passed('multi-turn reference moves across weeks without changing course metadata')

    before=board()
    ask('把本周二的英语改到本周三第5、6节，同时把本周二的体育那一次去掉，后续周保持原样')
    assert [(s['day'],s['start'],s['end'],s['weeks']) for s in blocks('english') if s['day']==3]==[(3,5,6,[1])]
    assert not any(s['day']==2 and 1 in s['weeks'] for s in blocks('english'))
    assert blocks('sport')[0]['weeks']==[2,3,4]
    passed('natural compound request changes both courses atomically')
    page.locator('#phone').screenshot(path=str(out/'timetable-change-batch.png'))
    page.reload();expect(page.locator('#connection')).to_have_text('本地服务已连接');page.locator('#ai-bubble').click()
    expect(page.locator('.ai-message.assistant')).to_have_count(6)
    assert blocks('sport')[0]['weeks']==[2,3,4]
    page.locator('[data-ai="undo"]').click()
    assert board()['courses']==before['courses'] and board()['sessions']==before['sessions']
    passed('refresh retains transaction and one undo restores all steps')

    before=board();reply=ask('英语以后都调一下吧')
    assert board()==before and ('时间' in reply or '星期' in reply or '哪' in reply)
    passed('incomplete move asks a follow-up without changing the schedule')
    # The moved class is visible in week 2, not accidentally left in week 1.
    page.locator('[data-ai="minimize"]').click();page.locator('[data-action="next-week"]').click()
    expect(page.locator(f'.course-block[data-id="{cppid}"]')).to_have_count(1)
    page.locator('#phone').screenshot(path=str(out/'timetable-change-week2.png'))
    assert not errors,errors
    report={'model':'deepseek-flash','realApi':True,'results':results,'reloadAndAtomicUndo':True,'week2CardVisible':True,'browserErrors':errors}
    (out/'timetable-change-live-result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    browser.close()
