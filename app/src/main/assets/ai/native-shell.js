import { createAiChat } from './ai-chat.js';
import { buildUpstreamRequest } from './request-builder.mjs';
import { operationContext, applyTimetableAction, undoTimetableAction, boardVersion } from './timetable-ai.js';
import { planTimetableImport } from './timetable-import.js';

const native = window.AndroidAI;
function result(raw) { const response=JSON.parse(raw); if(!response.ok)throw new Error(response.error||'本机操作失败');return response.value; }
const requests = new Map();
window.nativeReceive = (id, event) => {
  const pending=requests.get(id); if(!pending)return;
  if(event.error) { pending.fail(new Error(event.error));return; }
  if(event.status)pending.resolve(new Response(pending.stream,{status:event.status,headers:{'Content-Type':event.contentType}}));
  if(event.chunk) pending.controller.enqueue(Uint8Array.from(atob(event.chunk),c=>c.charCodeAt(0)));
  if(event.done) { pending.cleanup();pending.controller.close(); }
};
// All HTTPS is performed by Android OkHttp. Browser network access is disabled by CSP.
window.fetch = (url, options={}) => new Promise((resolve,reject)=>{
  if(url!=='/api/llm/chat') { reject(new Error('不支持此网络请求'));return; }
  const id=crypto.randomUUID(), signal=options.signal;
  if(signal?.aborted){reject(signal.reason);return;}
  let controller;
  const cleanup=()=>{requests.delete(id);signal?.removeEventListener('abort',abort);};
  const fail=error=>{cleanup();controller.error(error);reject(error);};
  const abort=()=>{native.cancel(id);fail(new DOMException('已停止','AbortError'));};
  const stream=new ReadableStream({start(c){controller=c;},cancel(){native.cancel(id);cleanup();}});
  requests.set(id,{controller,stream,resolve,fail,cleanup});signal?.addEventListener('abort',abort,{once:true});
  try { native.request(id,JSON.stringify(buildUpstreamRequest(JSON.parse(options.body)))); }
  catch(error){fail(error);}
});
const snapshot = () => result(native.snapshot());
function current(context) {
  const data=snapshot();
  if(data.board.id!==context.board.id)throw new Error('当前课表已切换，本次未执行，请重新发送');
  if(JSON.stringify(data.periods)!==JSON.stringify(context.periods))throw new Error('作息已改变，请重新核对');
  if(data.date!==context.date)throw new Error('日期已改变，请基于今天的课表重新发送');
  return data;
}
const timetable = {
  context(ids,weeks){ const d=snapshot();return operationContext(d.board,d.periods,d.date,d.viewedWeek,ids,weeks); },
  execute(decision,context,id){
    const d=current(context), r=applyTimetableAction(d.board,context,decision,id);
    if(r.changed) result(native.commit(d.token,JSON.stringify(r.board)));
    return r;
  },
  import(draft,context,strategy,id,previewOnly=false){
    const d=current(context),r=planTimetableImport(d.board,context,draft,strategy,id);
    if(!previewOnly&&r.changed)result(native.commit(d.token,JSON.stringify(r.board)));
    return r;
  },
  undo(receipt){const d=snapshot();if(d.board.id!==receipt.boardId)throw new Error('请先切换回原课表');result(native.commit(d.token,JSON.stringify(undoTimetableAction(d.board,receipt.id))));},
  canUndo(receipt){try{const b=snapshot().board;return b.id===receipt.boardId&&b.aiLastOperation?.id===receipt.id&&boardVersion(b)===b.aiLastOperation.afterVersion;}catch{return false;}},
};
const phone=document.querySelector('#phone'),settings=document.querySelector('#settings');
let noticeTimer, settingsOpen=false;
function notify(message){const n=document.querySelector('#notice');n.textContent=message;n.hidden=false;clearTimeout(noticeTimer);noticeTimer=setTimeout(()=>n.hidden=true,3000);}
const dialog=document.querySelector('#confirm');
function confirm(title,description,options){
  dialog.querySelector('h3').textContent=title;dialog.querySelector('p').textContent=description;
  const accept=dialog.querySelector('#accept-confirm'),cancel=dialog.querySelector('#cancel-confirm');
  accept.textContent=options.confirm||'确认';cancel.textContent=options.cancel||'取消';
  accept.onclick=()=>{dialog.close();options.onConfirm?.();};cancel.onclick=()=>dialog.close();dialog.showModal();
}
function navigate(page){
  settingsOpen=page==='llm';settings.hidden=!settingsOpen;
  if(settingsOpen){settings.innerHTML=chat.settingsPage().replaceAll('此浏览器中','此应用的加密存储中').replaceAll('本次浏览器会话','本次应用会话').replaceAll('当前浏览器','本机应用').replaceAll('预览日期','手机日期');chat.updatePage('llm');}
  else {settings.innerHTML='';chat.updatePage('timetable');chat.open();}
}
const chat=createAiChat({phone,navigate,notify,confirm,timetable,settingsStore:{
  load:()=>result(native.loadSettings()),save:value=>result(native.saveSettings(JSON.stringify(value))),
}});
document.addEventListener('click',event=>{
  const target=event.target.closest('button');if(!target)return;
  if(target.dataset.action==='back'){event.preventDefault();navigate('timetable');native.action('back');}
  if(['minimize','size','shrink','grow'].includes(target.dataset.ai)){
    event.preventDefault();event.stopImmediatePropagation();chat.flush();native.action(target.dataset.ai);
  }
  if(target.dataset.ai==='copy') {
    event.preventDefault();event.stopImmediatePropagation();
    // Use the already sanitized rendered message; clipboard contains no markup or credentials.
    const article=target.closest('article');native.copy(article.querySelector('.ai-markdown,.ai-user-text')?.innerText||'');notify('已复制');
  }
},true);
window.nativeShow = showSettings => navigate(showSettings?'llm':'timetable');
window.nativeRefresh = () => { if(!settingsOpen)chat.updatePage('timetable'); };
window.nativeSuspend = () => {chat.stop();chat.flush();dialog.close();};
window.nativeTheme = tokens => {for(const [key,value] of Object.entries(tokens)){phone.style.setProperty('--'+key,value);document.documentElement.style.setProperty('--'+key,value);}};
window.nativeBack = () => {const image=document.querySelector('.ai-image-viewer');if(image){image.remove();return true;}if(dialog.open){dialog.close();return true;}if(settingsOpen){navigate('timetable');return true;}return false;};
navigate('timetable');
