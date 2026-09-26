"""Run the actual APK assets in mobile Chromium; exercise bridge contract + real API.
Android codec/atomic storage/OkHttp are covered separately by JVM tests.
No APK secrets or demo boards are bundled by this test.
"""
import argparse,base64,json,os,requests,unicodedata
from pathlib import Path
from playwright.sync_api import sync_playwright,expect

parser=argparse.ArgumentParser();parser.add_argument('--key-file',required=True);parser.add_argument('--image',required=True);parser.add_argument('--only-weeks',action='store_true');args=parser.parse_args()
root=Path(__file__).resolve().parents[2];assets=root/'app/src/main/assets/ai';out=root/'preview/.runtime';out.mkdir(exist_ok=True)
key=Path(args.key_file).read_text(encoding='utf-8-sig').strip()
fixture=json.loads((root/'preview/test/change-fixture.json').read_text(encoding='utf-8'))
periods=[{'index':i+1,'begin':a,'end':b} for i,(a,b) in enumerate([('08:00','08:45'),('08:50','09:35'),('09:50','10:35'),('10:40','11:25'),('11:30','12:15'),('14:00','14:45'),('14:50','15:35'),('15:50','16:35'),('16:40','17:25'),('17:30','18:15'),('19:00','19:45'),('19:50','20:35'),('20:40','21:25')])]
requests_seen=[]
def upstream(prepared):
    data=json.loads(prepared)
    # Only runtime artifacts: useful for exercising the same payload via native OkHttp.
    safe={**data,'apiKey':''};requests_seen.append(safe)
    (out/('weeks-native-prepared-requests.json' if args.only_weeks else 'native-prepared-requests.json')).write_text(json.dumps(requests_seen,ensure_ascii=False),encoding='utf-8')
    try:
        response=requests.post(data['url'],json=data['body'],headers={'Authorization':'Bearer '+data['apiKey']},timeout=180,allow_redirects=False)
        if not response.ok:return {'error':f'HTTP {response.status_code}'}
        return {'status':response.status_code,'contentType':response.headers.get('Content-Type','application/json'),'chunk':base64.b64encode(response.content).decode(),'done':True}
    except Exception:return {'error':'Test upstream connection failed'}

with sync_playwright() as p:
    browser=p.chromium.launch(headless=True,executable_path=str(Path(os.environ['LOCALAPPDATA'])/'ms-playwright/chromium-1234/chrome-win64/chrome.exe'))
    page=browser.new_page(viewport={'width':392,'height':650},device_scale_factor=2,has_touch=True,is_mobile=True)
    errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
    page.expose_function('testUpstream',upstream)
    def asset(route):
        from urllib.parse import urlparse
        name=urlparse(route.request.url).path.lstrip('/') or 'index.html'
        f=assets/name
        if not f.is_file():route.fulfill(status=404,body='Missing');return
        mime={'html':'text/html','css':'text/css','json':'application/json'}.get(f.suffix[1:],'text/javascript')
        route.fulfill(status=200,body=f.read_bytes(),content_type=mime)
    page.route('https://appassets.androidplatform.net/**',asset)
    page.add_init_script(script='''
      const setup=SETUP;
      window.testBoard=setup.board;window.testRevision=1;window.testCommits=0;window.testFault=false;
      let config={provider:'deepseek',baseUrl:'https://api.deepseek.com',model:'deepseek-flash',vision:true,thinking:false,temperature:.7,maxTokens:4096,contextTurns:2,systemPrompt:'优先中文回答。',rememberKey:false};
      const ok=value=>JSON.stringify({ok:true,value});
      window.AndroidAI={
        snapshot:()=>ok({board:window.testBoard,token:String(testRevision),date:'2026-09-26',viewedWeek:1,periods:setup.periods}),
        commit:(token,board)=>{if(testFault||token!==String(testRevision))return JSON.stringify({ok:false,error:'模拟本机存储失败或旧快照'});testBoard=JSON.parse(board);testRevision++;testCommits++;return ok(true);},
        loadSettings:()=>ok({config,apiKey:setup.key}),saveSettings:raw=>{config=JSON.parse(raw).config;return ok(true);},
        request:(id,body)=>window.testUpstream(body).then(event=>window.nativeReceive(id,event)),
        cancel:()=>{},copy:()=>{},action:()=>{},
      };
    '''.replace('SETUP',json.dumps({'board':fixture,'key':key,'periods':periods},ensure_ascii=False)))
    page.goto('https://appassets.androidplatform.net/index.html');expect(page.locator('#ai-window')).to_be_visible()
    expect(page.locator('#ai-cache-status')).not_to_have_text('正在读取本机记录')
    def board():return page.evaluate('testBoard')
    def ask(text):
        page.locator('#ai-input').fill(text);page.locator('#ai-send').click()
        expect(page.locator('#ai-send')).to_have_attribute('aria-label','发送消息',timeout=240000)
        message=page.locator('.ai-message.assistant').last
        assert not message.locator('.has-error').count(),message.inner_text()
        return message.locator('.ai-markdown').inner_text()
    def pending():return page.evaluate("async ()=>(await (await import('/ai-storage.js')).readChat('timetable-conversations')).phase2.importDraft")
    try:
        if args.only_weeks:
            page.locator('[data-mode="timetable"]').click()
            before=board()
            ask('添加工科数分')
            assert board()==before and page.evaluate('testCommits')==0
            ask('星期天 1-12节')
            assert board()==before and page.evaluate('testCommits')==0
            ask('每一周')
            added=next(c for c in board()['courses'] if c['name']=='工科数分')
            slots=[s for s in board()['sessions'] if s['courseId']==added['id']]
            assert len(slots)==1 and page.evaluate('testCommits')==1
            s=slots[0];assert (s['day'],s['start'],s['end'],s['weeks'])==(7,1,12,list(range(1,17)))
            page.screenshot(path=str(out/'test02-02.02-weeks-live.png'))
            page.locator('[data-ai="undo"]').click()
            assert board()['courses']==before['courses'] and board()['sessions']==before['sessions']
            assert not errors,errors
            (out/'test02-02.02-weeks-browser.json').write_text(json.dumps({'passed':True,'apiCalls':len(requests_seen),'realApi':True,'commitBeforeWeeks':0,'addCommits':1,'weeks':list(range(1,17)),'undoPassed':True}),encoding='utf8')
            print('PASS APK UI real API: add course name -> ask time -> ask weeks -> every week -> exactly one commit -> undo',flush=True)
            raise SystemExit(0)
        ask('请记住，我的收藏编号是SEU-73。只需简短确认。')
        ask('明白了，谢谢，简单回复即可。')
        reply=ask('我刚才的收藏编号是什么？')
        assert '73' in reply and page.locator('#ai-memory').is_visible(),reply
        assert page.evaluate('testCommits')==0
        print('PASS APK assets: real API ordinary chat, automatic summary, preserved fact, zero timetable writes',flush=True)
        page.locator('[data-mode="timetable"]').click();before=board()
        ask('周六第一二节帮我安排工科数学分析，地点礼东。')
        assert board()==before,'Weeks were not clarified before addition'
        ask('只安排第二周，老师先不填。')
        course=next(c for c in board()['courses'] if c['name']=='工科数学分析')
        assert any(s['courseId']==course['id'] and s['weeks']==[2] and s['day']==6 for s in board()['sessions'])
        ask('刚加的这门课由王老师授课，学分3.5，备注带计算器。')
        course=next(c for c in board()['courses'] if c['id']==course['id'])
        assert course['teacher']=='王老师' and course['credit']==3.5
        ask('这门数学课第二周周六的那一次挪到同一周周日第三四节，地点还是礼东。')
        assert any(s['courseId']==course['id'] and s['day']==7 and s['start']==3 and s['end']==4 and s['weeks']==[2] for s in board()['sessions'])
        ask('刚才移到第二周周日第三四节的工科数学分析，换成一门新课程CPP，时间地点不变，新课的老师、学分、备注留空。')
        cpp=next(c for c in board()['courses'] if c['name'].upper()=='CPP')
        assert cpp['teacher']=='' and cpp['credit']=='' and cpp['note']==''
        ask('请删除整门CPP，包括它所有周次的所有课次。')
        assert board()['courses']==before['courses'] and board()['sessions']==before['sessions']
        print('PASS APK assets: real semantic add, ask weeks, metadata, move, replace, delete with operation summaries',flush=True)
        # Import the user screenshot through the exact APK attachment pipeline.
        page.locator('#ai-file').set_input_files(args.image);expect(page.locator('.ai-attachment')).to_have_count(1)
        commits=page.evaluate('testCommits')
        ask('请识别这张官方课表。只导入可见的周一到周五；这是2026年9月21日至25日，即第1周，只安排第1周。老师学分备注全部留空。先列清单让我确认。')
        draft=pending()['draft'];assert page.evaluate('testCommits')==commits
        expected={'数字电路基础':[(1,1,2,'教三-502'),(5,3,4,'教三-502')],'高阶外语':[(4,1,2,'教七-210')],
          '大学物理(B)II':[(2,3,4,'教四-204'),(5,1,2,'教四-204')],'数学物理方法':[(1,6,7,'教六-301')],
          '体育III':[(2,6,7,'四号馆跆拳道房')],'固体物理基础':[(1,8,9,'教三-102'),(5,9,10,'教三-102')],
          '马克思主义基本原理':[(2,8,10,'教六-202')],'中共党史':[(2,11,12,'教二-109')],'艺术与科技':[(4,11,13,'教七-105')]}
        def norm(s):return unicodedata.normalize('NFKC',s).replace(' ','').replace('–','-').replace('－','-').replace('—','-')
        actual={norm(c['name']):sorted((s['day'],s['start'],s['end'],norm(s['room'])) for s in c['sessions']) for c in draft['courses']}
        assert actual=={norm(k):sorted(v) for k,v in expected.items()},actual
        assert not draft['issues'] and all(s['weeks']==[1] for c in draft['courses'] for s in c['sessions'])
        page.screenshot(path=str(out/'test02-image-review.png'))
        page.locator('[data-strategy="weeks"]').click();expect(page.locator('dialog')).to_be_visible()
        assert page.evaluate('testCommits')==commits
        page.locator('#accept-confirm').click();expect(page.locator('[data-ai="import-confirm"]')).to_have_count(0)
        assert all(s['weeks']==[2,3,4] for s in board()['sessions'] if s['courseId'] in ['english','sport'])
        assert len(board()['sessions'])==15
        page.locator('[data-ai="undo"]').click();assert board()['courses']==before['courses'] and board()['sessions']==before['sessions']
        print('PASS APK assets: official photo 9 subjects / 12 slots, no pre-confirm writes, week coverage + atomic undo',flush=True)
        page.locator('[data-ai="settings"]').first.click();expect(page.locator('#llm-settings-form')).to_be_visible()
        page.locator('[name="model"]').fill('deepseek-flash');page.locator('[data-ai="save-settings"]').click()
        assert not page.evaluate("Object.keys(localStorage).some(k=>k.includes('api-key'))")
        page.locator('[data-action="back"]').click()
        # Inject disk failure and verify that a valid operation is not reported as success.
        page.evaluate('testFault=true');old=board()
        page.locator('#ai-input').fill('整门删除英语课。');page.locator('#ai-send').click()
        expect(page.locator('#ai-send')).to_have_attribute('aria-label','发送消息',timeout=240000)
        assert page.locator('.ai-message.assistant').last.locator('.has-error').count()==1 and board()==old
        print('PASS APK assets: failed native commit leaves board unchanged and shows failure; key stays out of browser storage',flush=True)
        assert not errors,errors
        (out/'test02-native-browser-result.json').write_text(json.dumps({'realApi':True,'apiCalls':len(requests_seen),'officialSubjects':9,'officialSlots':12,'pageErrors':errors,'commitCount':page.evaluate('testCommits')},ensure_ascii=False,indent=2),encoding='utf-8')
    finally:
        page.screenshot(path=str(out/'test02-native-last.png'));browser.close()
