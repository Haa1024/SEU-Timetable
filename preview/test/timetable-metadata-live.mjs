// Real API regression for short, colloquial metadata follow-ups.
import { readFile, writeFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
import { loadSource } from '../source.mjs';
import { operationContext, applyTimetableAction } from '../public/timetable-ai.js';
import { requestTimetableDecision } from '../public/timetable-request.js';
const apiKey = (await readFile(process.argv[2], 'utf8')).replace(/^\uFEFF/, '').trim();
const fixture = JSON.parse(await readFile(new URL('./change-fixture.json', import.meta.url), 'utf8'));
fixture.courses.push({id:'cpp',name:'CPP',teacher:'',credit:'',note:'',colorOverride:null,code:'',classNo:''});
fixture.sessions.push({id:'cpp-s',courseId:'cpp',day:6,start:1,end:2,weeks:[1],room:''});
const { periods } = await loadSource();
const results = [];
for (const forceEmpty of process.argv.includes('--fallback') ? [false,true] : [false]) {
  for (const phrase of ['cpp老师设置为是王鑫','这门cpp是王鑫老师带的','给CPP记一下，任课老师王鑫']) {
    let board = structuredClone(fixture);
    const messages = [{role:'user',content:'本周六第1、2节加一门CPP'}, {role:'assistant',content:'已添加「CPP」。可以继续补充老师、教室、学分和备注。'}];
    for (const input of [phrase, 'cpp课在中山院上课']) {
      messages.push({role:'user',content:input});
      const context = operationContext(board,periods,'2026-09-26',1,['cpp']);
      const responses = []; let calls = 0;
      const decision = await requestTimetableDecision({apiKey,config:{model:'deepseek-flash',maxTokens:2048},messages,context},{fetchImpl:async(url,opts)=>{
        calls++;
        if(forceEmpty && calls===1) return new Response(JSON.stringify({choices:[{finish_reason:'stop',message:{content:''}}]}));
        const response = await fetch(`http://127.0.0.1:4173${url}`,{...opts,headers:{...opts.headers,Origin:'http://127.0.0.1:4173'},signal:AbortSignal.timeout(180000)});
        const data = await response.clone().json(); responses.push(data.choices?.[0] || {error:data.error});
        return response;
      }});
      const result = applyTimetableAction(board,context,decision,`meta-${results.length}`);
      board = result.board;
      assert.equal(board.courses.find(c=>c.id==='cpp').teacher,'王鑫');
      if(input.includes('中山院')) assert.equal(board.sessions.find(s=>s.courseId==='cpp').room,'中山院');
      assert.equal(board.courses.length,fixture.courses.length);
      assert.equal(board.sessions.length,fixture.sessions.length);
      messages.push({role:'assistant',content:result.text});
      results.push({input,forceEmpty,calls,responses,decision,result:result.text});
      await writeFile(new URL('../.runtime/timetable-metadata-live.json',import.meta.url),JSON.stringify({realApi:true,results},null,2));
      console.log(`PASS ${forceEmpty?'empty recovery':'normal'}: ${input} (${calls} requests)`);
    }
  }
}
