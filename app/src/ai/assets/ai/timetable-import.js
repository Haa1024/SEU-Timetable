import { applyTimetableAction, boardSnapshot, boardVersion, recordOperation } from './timetable-ai.js';
import { weeksLabel } from './model.js';

const fail = text => {throw new Error(text);};
const object = x => x && typeof x==='object' && !Array.isArray(x);
const norm = s => String(s||'').normalize('NFKC').replace(/\s/g,'').toLowerCase();
export function validateImportDraft(raw,context) {
  if(!object(raw) || raw.type!=='import_preview' || !Array.isArray(raw.courses) || raw.courses.length>60 ||
    !Array.isArray(raw.issues) || raw.issues.length>60 || JSON.stringify(raw).length>120000) fail('图片识别清单格式不完整，课表未修改');
  const cleanText=(x,label,max=200)=>{if(x==null)return '';if(typeof x!=='string'||x.length>max)fail(`${label}格式无效`);return x.trim();};
  const number=(x,min,max,label)=>{if(x==null)return null;if(!Number.isInteger(x)||x<min||x>max)fail(`${label}超出当前课表范围`);return x;};
  const draft={type:'import_preview',issues:raw.issues.map(x=>cleanText(x,'疑问',1000)),courses:raw.courses.map(c=>{
    if(!object(c)||!Array.isArray(c.sessions)||c.sessions.length>20)fail('图片中的课程时间段格式无效');
    if(c.credit!=null&&c.credit!==''&&(typeof c.credit!=='number'||!Number.isFinite(c.credit)||c.credit<0||c.credit>100))fail('学分无效');
    return {name:cleanText(c.name,'课程名'),teacher:cleanText(c.teacher,'老师'),credit:c.credit??'',note:cleanText(c.note,'备注',2000),sessions:c.sessions.map(s=>{
      if(!object(s))fail('时间段无效');
      let weeks=null;
      if(s.weeks!=null){if(!Array.isArray(s.weeks)||!s.weeks.length||s.weeks.length>60)fail('周次无效');weeks=[...new Set(s.weeks.map(w=>number(w,1,context.board.term.totalWeeks,'周次')))];if(weeks.includes(null))fail('周次无效');weeks.sort((a,b)=>a-b);}
      const start=number(s.start,1,context.periods.length,'起始节'),end=number(s.end,1,context.periods.length,'结束节');
      if(start!==null&&end!==null&&end<start)fail('结束节不能早于起始节');
      return {day:number(s.day,1,7,'星期'),start,end,weeks,room:cleanText(s.room,'教室')};
    })};
  })};
  return draft;
}
export function importMissing(draft) {
  const missing=[...draft.issues.filter(Boolean)];
  if(!draft.courses.length)missing.push('没有识别到课程，请提供清晰课表图片');
  draft.courses.forEach((c,i)=>{
    const label=c.name||`第${i+1}门课程`;
    if(!c.name)missing.push(`${label}缺少课程名`);
    if(!c.sessions.length)missing.push(`${label}缺少上课时间`);
    c.sessions.forEach((s,j)=>{
      if([s.day,s.start,s.end].some(v=>v===null))missing.push(`${label}时段${j+1}缺少星期或节次`);
      if(!s.weeks?.length)missing.push(`${label}时段${j+1}缺少周次`);
    });
  });
  return missing;
}
const md = s=>String(s).replace(/[\\`*_{}\[\]<>|#!]/g,'\\$&').replace(/\r?\n/g,' ');
export function importDescription(draft) {
  const slots=draft.courses.reduce((sum,c)=>sum+c.sessions.length,0);
  const lines=[`识别清单：${draft.courses.length} 门课程 · ${slots} 个时间段（尚未写入课表）`];
  draft.courses.forEach((c,i)=>{
    lines.push(`\n**${i+1}. ${md(c.name||'课程名待确认')}**`, `老师：${md(c.teacher||'未提供')} · 学分：${c.credit===''?'未提供':c.credit} · 备注：${md(c.note||'无')}`);
    for(const s of c.sessions)lines.push(`- 周${s.day?'一二三四五六日'[s.day-1]:'？'} 第 ${s.start??'？'}–${s.end??'？'} 节 · ${s.weeks?`第 ${weeksLabel(s.weeks)} 周`:'周次待确认'} · ${md(s.room||'教室未提供')}`);
  });
  const missing=importMissing(draft);
  lines.push(missing.length?`\n请先补充或纠正：\n${missing.map(x=>`- ${md(x)}`).join('\n')}`:'\n请核对以上所有信息，再选择下方导入方式；也可以继续发消息纠正。');
  return lines.join('\n');
}
export function planTimetableImport(board,context,raw,strategy,requestId) {
  if(!['append','weeks','all'].includes(strategy))fail('请选择导入方式');
  if(boardVersion(board)!==JSON.stringify(context.board))fail('课表在识别后已改变，请重新识别或更新清单后再确认');
  if(board.aiRequestIds?.includes(requestId))fail('这张清单已导入，未重复执行');
  const draft=validateImportDraft(raw,context),missing=importMissing(draft);
  if(missing.length)fail(`请先补齐识别清单：${missing.join('；')}`);
  let working=structuredClone(board);
  const weeks=[...new Set(draft.courses.flatMap(c=>c.sessions.flatMap(s=>s.weeks)))].sort((a,b)=>a-b);
  let removed=0, skipped=0, created=[], warnings=[];
  if(strategy==='all') {
    removed=working.sessions.reduce((n,s)=>n+s.weeks.length,0);
    working.courses=[];working.sessions=[];working.unplaced=[];
  } else if(strategy==='weeks') {
    const affected=new Set();
    working.sessions=working.sessions.flatMap(s=>{
      const remaining=s.weeks.filter(w=>!weeks.includes(w));
      removed+=s.weeks.length-remaining.length;if(remaining.length!==s.weeks.length)affected.add(s.courseId);
      return remaining.length?[{...s,weeks:remaining}]:[];
    });
    working.courses=working.courses.filter(c=>!affected.has(c.id)||working.sessions.some(s=>s.courseId===c.id)||(working.unplaced||[]).some(u=>u.id===c.id));
  }
  for(const [i,c] of draft.courses.entries()) {
    const sessions=c.sessions.flatMap(s=>{
      const remaining=s.weeks.filter(w=>!working.sessions.some(t=>{
        const existing=working.courses.find(x=>x.id===t.courseId);
        return norm(existing?.name)===norm(c.name)&&(!c.teacher||norm(existing?.teacher)===norm(c.teacher))&&t.day===s.day&&t.start===s.start&&t.end===s.end&&(!s.room||norm(t.room)===norm(s.room))&&t.weeks.includes(w);
      }));
      skipped+=s.weeks.length-remaining.length;
      return remaining.length?[{...s,weeks:remaining}]:[];
    });
    if(!sessions.length)continue;
    const stepContext={...context,board:boardSnapshot(working)};
    const result=applyTimetableAction(working,stepContext,{reply:'导入课程',action:{type:'add',...c,sessions}},`${requestId}:${i}`);
    working=result.board;created.push(...result.receipt.createdCourseIds);
    warnings.push(...result.text.split('\n').filter(x=>x.startsWith('时间冲突：')));
  }
  const scope=strategy==='append'?'追加（保留原课程）':strategy==='weeks'?`覆盖第 ${weeksLabel(weeks)} 周的全部课程，保留其他周和未排课`:'覆盖整张课表（包括所有周和未排课）';
  const impact=`方式：${scope}\n将移除 ${removed} 个原课次，新增 ${created.length} 门课程，跳过 ${skipped} 个已有课次（保留原信息）。${warnings.length?'\n'+[...new Set(warnings)].join('\n'):''}`;
  if(boardVersion(working)===boardVersion(board))return {board,changed:false,text:'清单中的课程已经存在，课表未修改。',impact};
  return {...recordOperation(board,working,{type:'import'},requestId,`已导入图片课表。\n${impact.replace('将移除','移除')}\n可撤销整次导入。`,created.at(-1),created),impact};
}
