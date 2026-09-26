// Deliberately a classic script: module parsing/import failures must be visible too.
(function () {
  var finished = false;
  function fail() {
    if (finished) return;
    finished = true;
    var boot = document.getElementById('boot');
    if (boot) boot.textContent = 'AI 页面启动失败，请点击重新加载。';
    // Never forward exception text, settings or conversation contents to native logs.
    if (window.AndroidAI) window.AndroidAI.action('startup-error');
  }
  window.addEventListener('error', fail);
  import('./native-shell.js').then(function () {
    finished = true;
    window.removeEventListener('error', fail);
    var boot = document.getElementById('boot');
    if (boot) boot.remove();
    window.AndroidAI.action('ready');
  }).catch(fail);
})();
