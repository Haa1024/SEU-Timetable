"""User-provided official SEU screenshot, real API, isolated browser storage."""
import argparse,json,os,unicodedata
from pathlib import Path
from playwright.sync_api import sync_playwright,expect
parser=argparse.ArgumentParser();parser.add_argument('--key-file',required=True);parser.add_argument('--image',required=True);args=parser.parse_args()
out=Path('preview/.runtime');key=Path(args.key_file).read_text(encoding='utf-8-sig').strip()
fixture=json.loads(Path('preview/test/change-fixture.json').read_text(encoding='utf-8'))
# Manually transcribed from the user's supplied screenshot, not model output.
expected={
 '数字电路基础':[(1,1,2,'教三-502'),(5,3,4,'教三-502')],
 '高阶外语':[(4,1,2,'教七-210')],
 '大学物理(B)II':[(2,3,4,'教四-204'),(5,1,2,'教四-204')],
 '数学物理方法':[(1,6,7,'教六-301')],
 '体育III':[(2,6,7,'四号馆跆拳道房')],
 '固体物理基础':[(1,8,9,'教三-102'),(5,9,10,'教三-102')],
 '马克思主义基本原理':[(2,8,10,'教六-202')],
 '中共党史':[(2,11,12,'教二-109')],
 '艺术与科技':[(4,11,13,'教七-105')],
}
def norm(s):return unicodedata.normalize('NFKC',s).replace(' ','').replace('–','-').replace('－','-').replace('—','-')
with sync_playwright() as p:
    browser=p.chromium.launch(headless=True,executable_path=str(Path(os.environ['LOCALAPPDATA'])/'ms-playwright/chromium-1234/chrome-win64/chrome.exe'))
    page=browser.new_page(viewport={'width':1440,'height':1100});errors=[];results=[]
    page.on('pageerror',lambda e:errors.append(str(e)))
    page.goto('http://127.0.0.1:4173/api/health')
    page.evaluate('''([board,key])=>{
      localStorage.setItem('seu-browser-preview-v1',JSON.stringify({page:'timetable',tab:'timetable',date:'2026-09-26',week:1,boards:{[board.id]:board},activeId:board.id}));
      sessionStorage.setItem('seu-llm-api-key',key);localStorage.setItem('seu-llm-config-v1',JSON.stringify({model:'deepseek-flash',maxTokens:4096}));
      localStorage.setItem('seu-ai-mode',JSON.stringify('timetable'));
    }''',[fixture,key])
    page.goto('http://127.0.0.1:4173');expect(page.locator('#connection')).to_have_text('本地服务已连接');page.locator('#ai-bubble').click()
    def board():return page.evaluate("JSON.parse(localStorage.getItem('seu-browser-preview-v1')).boards.phase2")
    def pending():return page.evaluate("async ()=>(await (await import('/ai-storage.js')).readChat('timetable-conversations')).phase2.importDraft")
    def ask(text):
        page.locator('#ai-input').fill(text);page.locator('#ai-send').click();expect(page.locator('#ai-send')).to_have_attribute('aria-label','发送消息',timeout=240000)
        last=page.locator('.ai-message.assistant').last;assert last.locator('.has-error').count()==0,last.inner_text()
        results.append({'input':text,'reply':last.locator('.ai-markdown').inner_text(),'draft':pending()})
        (out/'official-image-progress.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
    def verify(draft):
        actual={norm(c['name']):sorted((s['day'],s['start'],s['end'],norm(s['room'])) for s in c['sessions']) for c in draft['courses']}
        assert actual=={norm(k):sorted(v) for k,v in expected.items()},actual
        assert all(c['teacher']=='' and c['credit']=='' for c in draft['courses'])
    before=board();page.locator('#ai-file').set_input_files(args.image);expect(page.locator('.ai-attachment')).to_have_count(1)
    ask('请识别这张东南大学课表照片，列出所有能看到的课程、节次、教室和周次，我先核对。')
    assert board()==before;verify(pending()['draft'])
    print('PASS official image: all 9 subjects, 12 slots, weekdays, period ranges and rooms match manual transcription',flush=True)
    # Explicit clarification of visible area/date, including the absent weekend.
    ask('只导入图片里可见的周一到周五课程；未显示的周末不需要补识别。这是2026年9月21日至25日，也就是第1周，只安排这一周，老师学分备注都留空。请更新待确认清单。')
    assert board()==before;draft=pending()['draft'];verify(draft);assert not draft['issues'],draft['issues']
    assert all(s['weeks']==[1] for c in draft['courses'] for s in c['sessions'])
    page.locator('#phone').screenshot(path=str(out/'official-image-review.png'))
    # Save the approved proposal locally in this isolated context to exercise all
    # three confirmation paths without asking the model to generate it again.
    approved=pending()
    for strategy in ['append','weeks','all']:
        if strategy!='append':
            # Restore pending review through an inert page, avoiding pagehide saves.
            page.goto('http://127.0.0.1:4173/api/health')
            page.evaluate('''async draft=>{const s=await import('/ai-storage.js');const chats=await s.readChat('timetable-conversations');draft.id=crypto.randomUUID();chats.phase2.importDraft=draft;await s.saveChat(chats,'timetable-conversations');}''',approved)
            page.goto('http://127.0.0.1:4173');expect(page.locator('#connection')).to_have_text('本地服务已连接');page.locator('#ai-bubble').click()
        page.locator(f'[data-strategy="{strategy}"]').click();expect(page.locator('#modal-root dialog')).to_be_visible();assert board()['courses']==before['courses']
        page.get_by_role('button',name='确认导入',exact=True).click();expect(page.locator('[data-ai="import-confirm"]')).to_have_count(0)
        after=board();assert len(after['courses'])==(9 if strategy=='all' else 11)
        old=[s for s in after['sessions'] if s['courseId'] in ['english','sport']]
        if strategy=='all':assert not old
        else:assert all(s['weeks']==([1,2,3,4] if strategy=='append' else [2,3,4]) for s in old)
        assert sum(len(c['sessions']) for c in draft['courses'])==12
        assert len([s for s in after['sessions'] if s['courseId'] not in ['english','sport']])==12
        page.locator('[data-ai="undo"]').click();assert board()['courses']==before['courses'] and board()['sessions']==before['sessions']
        print('PASS official image confirmed '+strategy+' import and atomic undo',flush=True)
    assert not errors,errors
    (out/'official-image-live-result.json').write_text(json.dumps({'realApi':True,'userProvidedImage':Path(args.image).name,'expectedSubjects':9,'expectedSlots':12,'strategies':['append','weeks','all'],'results':results,'browserErrors':errors},ensure_ascii=False,indent=2),encoding='utf-8')
    browser.close()
