"""Real model acceptance: memory compaction and image review/confirmation.
Uses a generated timetable test image, not real SEU official schedule data.
"""
import argparse,json,os,html
from pathlib import Path
from playwright.sync_api import sync_playwright,expect
parser=argparse.ArgumentParser();parser.add_argument('--key-file',required=True);args=parser.parse_args()
out=Path('preview/.runtime');out.mkdir(exist_ok=True)
key=Path(args.key_file).read_text(encoding='utf-8-sig').strip()
fixture=json.loads(Path('preview/test/change-fixture.json').read_text(encoding='utf-8'))
names=['工科数学分析','数字电路基础','大学物理','CPP','体育III','马克思主义基本原理','高阶外语','固体物理基础','数学物理方法','概率论','通识选修','电路实验']
with sync_playwright() as p:
    browser=p.chromium.launch(headless=True,executable_path=str(Path(os.environ['LOCALAPPDATA'])/'ms-playwright/chromium-1234/chrome-win64/chrome.exe'))
    image_page=browser.new_page(viewport={'width':1800,'height':1250},device_scale_factor=1)
    rows=[]
    for row in range(4):
        cells=[]
        for day in range(7):
            i=row*7+day
            cells.append(f'<td><b>{html.escape(names[i])}</b><br>张老师<br>教三-{301+i}</td>' if i<len(names) else '<td></td>')
        rows.append(f'<tr><th>第{row*2+1}–{row*2+2}节</th>'+''.join(cells)+'</tr>')
    image_page.set_content('''<meta charset="utf-8"><style>body{font:24px "Microsoft YaHei";padding:22px;color:#111}h1{font-size:32px}table{border-collapse:collapse;width:100%;table-layout:fixed}th,td{border:2px solid #777;padding:18px 8px;height:180px;text-align:center;line-height:1.7}th{background:#eef3ec}b{font-size:25px}</style><h1>课程周视图 · 识别测试样例</h1><p>示例数据，用于核对识别功能。未指定教学周。</p><table><tr><th>节次</th>'''+''.join(f'<th>周{x}</th>' for x in '一二三四五六日')+'</tr>'+''.join(rows)+'</table>')
    image_page.screenshot(path=str(out/'timetable-image-fixture.png'),full_page=True);image_page.close()
    page=browser.new_page(viewport={'width':1440,'height':1100});errors=[];evidence=[];requests=[]
    page.on('pageerror',lambda e:errors.append(str(e)))
    def capture(req):
        if '/api/llm/chat' in req.url:
            data=req.post_data_json
            requests.append({'mode':data.get('mode','chat'),'imageReview':data.get('imageReview',False),'hasMemory':bool(data.get('memory')),'messageCount':len(data.get('messages',[]))})
    page.on('request',capture)
    # Seed from an inert same-origin page: an app page's pagehide would save its
    # own old conversation over an externally injected IndexedDB fixture.
    page.goto('http://127.0.0.1:4173/api/health')
    page.evaluate('''async ([board,key])=>{
      const k='seu-browser-preview-v1',s={page:'timetable',tab:'timetable',date:'2026-09-26',week:1,boards:{[board.id]:board},activeId:board.id};localStorage.setItem(k,JSON.stringify(s));
      sessionStorage.setItem('seu-llm-api-key',key);localStorage.setItem('seu-llm-config-v1',JSON.stringify({model:'deepseek-flash',maxTokens:4096,contextTurns:2}));
      const {saveChat}=await import('/ai-storage.js');
      await saveChat({messages:[
        {id:'u1',role:'user',text:'记住我的实验地点是礼东301。',status:'complete',createdAt:1},
        {id:'a1',role:'assistant',text:'记住了，实验地点是礼东301。',status:'complete',createdAt:2},
        {id:'u2',role:'user',text:'我喜欢中文回答。',status:'complete',createdAt:3},
        {id:'a2',role:'assistant',text:'好的。',status:'complete',createdAt:4},
        {id:'u3',role:'user',text:'今天先聊这些。',status:'complete',createdAt:5},
        {id:'a3',role:'assistant',text:'好的。',status:'complete',createdAt:6}],draft:{text:'',images:[]}});
    }''',[fixture,key])
    page.goto('http://127.0.0.1:4173');expect(page.locator('#connection')).to_have_text('本地服务已连接');page.locator('#ai-bubble').click()
    def board():return page.evaluate("JSON.parse(localStorage.getItem('seu-browser-preview-v1')).boards.phase2")
    def cached(key):return page.evaluate("async key=>(await import('/ai-storage.js')).readChat(key)",key)
    def ask(text):
        page.locator('#ai-input').fill(text);page.locator('#ai-send').click()
        expect(page.locator('#ai-send')).to_have_attribute('aria-label','发送消息',timeout=240000)
        last=page.locator('.ai-message.assistant').last
        assert last.locator('.has-error').count()==0,last.inner_text()
        answer=last.locator('.ai-markdown').inner_text();evidence.append({'input':text,'answer':answer})
        (out/'context-image-progress.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2),encoding='utf-8')
        return answer
    def passed(text):print('PASS '+text,flush=True)
    answer=ask('我之前告诉你的实验地点是什么？')
    (out/'context-debug.json').write_text(json.dumps({'chat':cached('conversation'),'requests':requests},ensure_ascii=False,indent=2),encoding='utf-8')
    assert '礼东301' in answer
    memory=cached('conversation')['memory'];assert '礼东301' in memory['text']
    assert len(cached('conversation')['messages'])==8
    passed('real summarization retains early fact and all local messages')
    page.reload();expect(page.locator('#connection')).to_have_text('本地服务已连接');page.locator('#ai-bubble').click()
    expect(page.locator('#ai-memory')).to_be_visible();assert cached('conversation')['memory']==memory
    page.locator('[data-mode="timetable"]').click();expect(page.locator('#ai-memory')).to_be_hidden()
    before=board();page.locator('#ai-file').set_input_files(str(out/'timetable-image-fixture.png'))
    expect(page.locator('.ai-attachment')).to_have_count(1)
    ask('请识别图片上的所有课程，准备导入课表。')
    assert board()==before
    pending=cached('timetable-conversations')['phase2']['importDraft']
    assert len(pending['draft']['courses'])==12,pending['draft']
    assert {c['name'] for c in pending['draft']['courses']}==set(names)
    for c in pending['draft']['courses']:
        i=names.index(c['name']);s=c['sessions'][0]
        assert s['day']==i%7+1 and s['start']==i//7*2+1 and s['end']==i//7*2+2,(c,i)
        assert s['weeks'] is None
    expect(page.locator('[data-ai="import-confirm"]').first).to_be_disabled()
    passed('real image recognizes 12 courses and every time cell, asks weeks, writes nothing')
    ask('全部只安排在第1周，课程名称和老师地点都按图片，先给我核对清单。')
    assert board()==before
    pending=cached('timetable-conversations')['phase2']['importDraft'];assert not pending['draft']['issues'],pending['draft']['issues']
    assert all(s['weeks']==[1] for c in pending['draft']['courses'] for s in c['sessions'])
    expect(page.locator('[data-strategy="weeks"]')).to_be_enabled()
    page.locator('#phone').screenshot(path=str(out/'image-import-review.png'))
    # Third operation turn forces old image summarization; draft survives independently.
    ask('CPP的老师改为王鑫，其余识别结果保持原样。')
    assert board()==before
    ops=cached('timetable-conversations')['phase2'];assert ops['memory']
    assert len(ops['importDraft']['draft']['courses'])==12
    assert next(c for c in ops['importDraft']['draft']['courses'] if c['name']=='CPP')['teacher']=='王鑫'
    assert cached('conversation')['memory']==memory
    passed('operation compression preserves pending image draft and correction without cross-mode memory')
    page.reload();expect(page.locator('#connection')).to_have_text('本地服务已连接');page.locator('#ai-bubble').click()
    expect(page.locator('[data-strategy="weeks"]')).to_be_visible();assert board()==before
    page.locator('[data-strategy="weeks"]').click();expect(page.locator('#modal-root dialog')).to_be_visible()
    assert board()==before
    page.get_by_role('button',name='确认导入',exact=True).click()
    expect(page.locator('[data-ai="import-confirm"]')).to_have_count(0)
    after=board();assert len(after['courses'])==14
    for s in after['sessions']:
        if s['courseId'] in ['english','sport']:assert s['weeks']==[2,3,4]
    assert sum(c['name']=='CPP' for c in after['courses'])==1
    assert cached('timetable-conversations')['phase2'].get('importDraft') is None
    passed('confirmation replaces only week 1, preserves future weeks, and consumes draft')
    page.locator('[data-ai="undo"]').click();assert board()['courses']==before['courses'] and board()['sessions']==before['sessions']
    passed('one undo restores entire image import')
    assert not errors,errors
    (out/'context-image-live-result.json').write_text(json.dumps({'realApi':True,'imageIsSyntheticFixture':True,'requests':requests,'results':evidence,'browserErrors':errors},ensure_ascii=False,indent=2),encoding='utf-8')
    browser.close()
