import { buildMessages } from './ai-core.js';

// Conservative character/image budgets, not a claim about provider tokenization.
const RECENT_BUDGET = 24000, SUMMARY_CHUNK = 18000;
const weight = m => (m.text || '').length + (m.images?.length || 0) * 4000;
function turnsOf(history) {
  const turns = [];
  for (const m of history) {
    if (m.role === 'user') turns.push([m]);
    else if (turns.length && m.role === 'assistant') turns.at(-1).push(m);
  }
  return turns;
}
export function contextPlan(history, memory, config) {
  const boundary = memory?.throughId ? history.findIndex(m => m.id === memory.throughId) : -1;
  const usable = boundary >= 0 && typeof memory?.text === 'string';
  const turns = turnsOf(usable ? history.slice(boundary + 1) : history);
  let keep = Math.min(Math.max(1, config.contextTurns), 30, turns.length);
  while (keep > 1 && turns.slice(-keep).flat().reduce((sum,m)=>sum+weight(m),0) > RECENT_BUDGET) keep--;
  return { memory: usable ? memory : null, older: turns.slice(0,turns.length-keep).flat(), recent: turns.slice(-keep).flat() };
}
function summaryChunks(messages) {
  const chunks = []; let chunk = [], size = 0;
  for (const m of messages) {
    // Failed and undone outcomes must remain explicit in a compressed history.
    const entry = { role: m.role, text: m.text || '', images: m.images || [], status: 'complete' };
    if (m.role === 'assistant') entry.text += `\n[结果状态:${m.undone ? '已撤销' : m.status}${m.error ? `；${m.error}` : ''}；${m.operationStatus || '聊天'}]`;
    const pieces=[];
    for(let i=0;i<entry.text.length;i+=12000)pieces.push({...entry,text:entry.text.slice(i,i+12000),images:[]});
    for(let i=0;i<entry.images.length;i+=2)pieces.push({...entry,text:'以上历史消息的附图（仅总结内容）',images:entry.images.slice(i,i+2)});
    for(const piece of pieces) {
      const cost = weight(piece);
      if (chunk.length && (size+cost > SUMMARY_CHUNK || chunk.length >= 20)) { chunks.push(chunk); chunk=[]; size=0; }
      chunk.push(piece); size+=cost;
    }
  }
  if(chunk.length) chunks.push(chunk);
  return chunks;
}
function wireEntries(entries, config) {
  // buildMessages groups by users, so keep a leading assistant outcome explicitly.
  return entries.flatMap(m => m.role === 'assistant' ? [{role:'assistant',content:m.text}] : buildMessages([m], {...config,contextTurns:1}));
}
export async function prepareContext(history, memory, config, apiKey, {signal,fetchImpl=fetch,onCompress=()=>{},operationMode=false}={}) {
  const plan = contextPlan(history,memory,config);
  let nextMemory = plan.memory;
  if(plan.older.length) {
    onCompress();
    let summary = nextMemory?.text || '';
    for(const chunk of summaryChunks(plan.older)) {
      signal?.throwIfAborted();
      const response = await fetchImpl('/api/llm/chat',{method:'POST',headers:{'Content-Type':'application/json'},signal,
        body:JSON.stringify({config,apiKey,mode:'summary',memory:summary,messages:wireEntries(chunk,config),stream:false})});
      const data = await response.json();
      if(!response.ok || data.error) throw new Error(`上下文压缩失败，原记录已保留：${typeof data.error==='string'?data.error:data.error?.message||response.status}`);
      const choice=data.choices?.[0], text=choice?.message?.content;
      if(choice?.finish_reason!=='stop' || typeof text!=='string' || !text.trim() || text.length>8000) throw new Error('上下文摘要不完整，原记录已保留，请重试');
      summary=text.trim();
    }
    signal?.throwIfAborted();
    nextMemory={text:summary,throughId:plan.older.at(-1).id,updatedAt:Date.now(),count:(nextMemory?.count||0)+1};
  }
  const recent = operationMode ? plan.recent.map(m => m.role === 'assistant' && m.status === 'error'
    ? {...m, status:'complete', text:`[应用执行失败，课表未修改] ${m.error || '上次请求失败'}。请结合用户后续补充与当前课表重新判断。`}
    : m) : plan.recent;
  return { memory:nextMemory,messages:buildMessages(recent,{...config,contextTurns:30}) };
}
