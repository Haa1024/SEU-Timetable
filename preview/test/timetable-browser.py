"""Isolated browser acceptance. --live uses the supplied key file, never logs it.
Run from repo: python preview/test/timetable-browser.py --live --key-file <path>
Requires locally installed Playwright Chromium; outputs ignored .runtime artifacts.
"""
import argparse
import json
import os
from pathlib import Path
from playwright.sync_api import sync_playwright, expect

args = argparse.ArgumentParser()
args.add_argument('--live', action='store_true')
args.add_argument('--key-file')
opts = args.parse_args()
out = Path('preview/.runtime')
out.mkdir(exist_ok=True)

with sync_playwright() as p:
    executable = Path(os.environ['LOCALAPPDATA']) / 'ms-playwright/chromium-1234/chrome-win64/chrome.exe'
    browser = p.chromium.launch(headless=True, executable_path=str(executable))
    page = browser.new_page(viewport={'width': 1440, 'height': 1080})
    errors, requests, replies, checks = [], [], [], []
    page.on('pageerror', lambda e: errors.append(str(e)))
    page.on('request', lambda r: requests.append(r.post_data_json) if '/api/llm/chat' in r.url else None)
    def record_response(response):
        if '/api/llm/chat' in response.url and requests[-1].get('mode') == 'timetable':
            try:
                data = response.json()
                (out/'timetable-ai-last-model.json').write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
            except Exception:
                pass
    page.on('response', record_response)
    page.goto('http://127.0.0.1:4173')
    expect(page.locator('#connection')).to_have_text('本地服务已连接')
    source = page.request.get('http://127.0.0.1:4173/api/source').json()
    key = Path(opts.key_file).read_text(encoding='utf-8-sig').strip() if opts.live else 'test-key'
    page.evaluate("""([key]) => {
        sessionStorage.setItem('seu-llm-api-key', key);
        localStorage.setItem('seu-llm-config-v1', JSON.stringify({model:'deepseek-flash', maxTokens:2048}));
        const s=JSON.parse(localStorage.getItem('seu-browser-preview-v1')); s.date='2026-09-26';s.week=1;s.page=s.tab='timetable';localStorage.setItem('seu-browser-preview-v1',JSON.stringify(s));
    }""", [key])
    page.reload()
    expect(page.locator('#ai-bubble')).to_be_visible()
    page.locator('#ai-bubble').click()
    page.locator('[data-mode="timetable"]').click()
    expect(page.locator('[data-mode="timetable"]')).to_have_attribute('aria-pressed', 'true')
    expect(page.locator('#ai-operation-context')).to_contain_text('先追问')
    mocks = []
    if not opts.live:
        def route_model(route):
            answer = mocks.pop(0)
            route.fulfill(content_type='application/json', body=json.dumps({'choices':[{'finish_reason':'stop','message':{'content':json.dumps(answer,ensure_ascii=False)}}]}))
        page.route('**/api/llm/chat', route_model)

    def board():
        return page.evaluate("JSON.parse(localStorage.getItem('seu-browser-preview-v1')).boards.fixture") or source['board']

    def ask(text, mock):
        if not opts.live: mocks.append(mock)
        count = page.locator('.ai-message.assistant').count()
        page.locator('#ai-input').fill(text)
        page.locator('#ai-send').click()
        expect(page.locator('.ai-message.assistant')).to_have_count(count + 1)
        expect(page.locator('#ai-send')).to_have_attribute('aria-label', '发送消息', timeout=180000)
        failure = page.locator('.ai-message.assistant').last.locator('.has-error')
        assert failure.count() == 0, failure.inner_text() if failure.count() else ''
        reply = page.locator('.ai-message.assistant .ai-markdown').last.inner_text()
        replies.append({'input':text,'reply':reply})
        (out/'timetable-ai-progress.json').write_text(json.dumps(replies,ensure_ascii=False,indent=2),encoding='utf-8')
        return reply

    def passed(name):
        checks.append(name)
        print('PASS ' + name, flush=True)

    initial = board()
    reply = ask('添加一个课程，时间：星期六的12节课，工科数学分析，在礼东上课', {'reply':'请问安排在第几周？','action':None})
    assert board() == initial
    assert '周' in reply
    passed('missing weeks asks before writing')
    reply = ask('第1到16周，每周都上', {'reply':'添加','action':{'type':'add','name':'工科数学分析','sessions':[{'day':6,'start':1,'end':2,'weeks':list(range(1,17)),'room':'礼东'}]}})
    b = board(); course = next(c for c in b['courses'] if c['name'] == '工科数学分析')
    blocks = [s for s in b['sessions'] if s['courseId'] == course['id']]
    assert len(blocks) == 1 and blocks[0]['day'] == 6 and blocks[0]['start'] == 1 and blocks[0]['end'] == 2
    assert blocks[0]['weeks'] == list(range(1,17)) and blocks[0]['room'] == '礼东'
    assert all(word in reply for word in ['老师','学分','备注'])
    assert len(b['courses']) == len(initial['courses']) + 1
    expect(page.locator('#app .course-block').filter(has_text='工科数学分析')).to_have_count(1)
    passed('real persisted add and visible timetable card')
    page.locator('#phone').screenshot(path=str(out/'timetable-ai-added.png'))
    ask('老师是张老师，学分3.5，备注是带计算器', {'reply':'补充','action':{'type':'metadata','courseId':course['id'],'teacher':'张老师','credit':3.5,'note':'带计算器'}})
    c = next(c for c in board()['courses'] if c['id'] == course['id'])
    assert c['teacher'] == '张老师' and c['credit'] == 3.5 and c['note'] == '带计算器'
    assert len(board()['courses']) == len(b['courses'])
    passed('optional follow-up updates same course')
    ask('删除本周六的工科数学分析，只删这一次', {'reply':'删除','action':{'type':'delete','target':{'name':'工科数学分析','day':6},'scope':'sessions','weeks':[1]}})
    assert next(s for s in board()['sessions'] if s['courseId'] == course['id'])['weeks'] == list(range(2,17))
    page.locator('[data-ai="undo"]').click()
    assert next(s for s in board()['sessions'] if s['courseId'] == course['id'])['weeks'] == list(range(1,17))
    passed('single occurrence delete and undo retain other weeks')
    before = board()
    ask('先别动课表，我只讨论一下把工科数学分析改成CPP并调到周二的可能性，不要执行。', {'reply':'可以讨论，目前未修改课表。','action':None})
    assert board() == before
    passed('discussion does not execute a replacement')
    ask('删除整门工科数学分析，包括全部周次和时间段', {'reply':'删除','action':{'type':'delete','target':{'name':'工科数学分析'},'scope':'course','weeks':None}})
    assert not any(c['id'] == course['id'] for c in board()['courses'])
    assert not any(s['courseId'] == course['id'] for s in board()['sessions'])
    assert board()['courses'] == initial['courses'] and board()['sessions'] == initial['sessions']
    passed('whole-course delete restores original course data')
    page.locator('#phone').screenshot(path=str(out/'timetable-ai-deleted.png'))
    page.reload(); page.locator('#ai-bubble').click()
    expect(page.locator('[data-mode="timetable"]')).to_have_attribute('aria-pressed','true')
    expect(page.locator('.ai-message.assistant')).to_have_count(6)
    assert not any(c['id'] == course['id'] for c in board()['courses'])
    page.locator('[data-ai="undo"]').click()
    assert any(c['id'] == course['id'] for c in board()['courses'])
    passed('operation history, data and undo survive reload')
    page.locator('[data-mode="chat"]').click()
    expect(page.locator('.ai-message')).to_have_count(0)
    before = board()
    ask('删除我的工科数学分析课程', {'reply':'普通聊天不执行课表操作，请切换课表操作模式。','action':None})
    assert board() == before
    assert 'context' not in requests[-1] and requests[-1].get('mode') != 'timetable'
    page.locator('[data-mode="timetable"]').click()
    expect(page.locator('.ai-message.assistant')).to_have_count(6)
    passed('normal chat isolated from operations and timetable context')
    assert not errors, errors
    page.locator('[data-ai="size"]').click()
    page.locator('#phone').screenshot(path=str(out/'timetable-ai-final.png'))
    # Credentials are deliberately omitted from all artifacts.
    report = {'liveApi':opts.live,'model':'deepseek-flash','checks':checks,'replies':replies,'browserErrors':errors}
    (out/('timetable-ai-live-result.json' if opts.live else 'timetable-ai-browser-result.json')).write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    browser.close()
