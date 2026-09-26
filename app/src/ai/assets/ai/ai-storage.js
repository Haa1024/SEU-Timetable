let opening;
function database() {
  opening ??= new Promise((resolve, reject) => {
    const request = indexedDB.open('seu-ai-chat', 1);
    request.onupgradeneeded = () => request.result.createObjectStore('local');
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
  return opening;
}
export async function readChat(key = 'conversation') {
  const db = await database();
  return new Promise((resolve, reject) => {
    const transaction = db.transaction('local'), request = transaction.objectStore('local').get(key);
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}
let writes = Promise.resolve();
export function saveChat(value, key = 'conversation') {
  const snapshot = structuredClone(value);
  const operation = writes.catch(() => {}).then(async () => {
    const db = await database();
    return new Promise((resolve, reject) => {
      const tx = db.transaction('local', 'readwrite');
      tx.objectStore('local').put(snapshot, key);
      tx.oncomplete = () => resolve(); tx.onerror = () => reject(tx.error); tx.onabort = () => reject(tx.error || new Error('本机缓存写入中断'));
    });
  });
  writes = operation;
  return operation;
}
