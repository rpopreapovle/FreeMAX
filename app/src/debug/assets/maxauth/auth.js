(function () {
  var last = null;
  function check() {
    try {
      var v = window.localStorage.getItem('__oneme_auth');
      if (v && v !== last) {
        last = v;
        browser.runtime.sendNativeMessage('browser', { type: 'MAX_AUTH', auth: v });
      }
    } catch (e) { /* storage blocked: ignore */ }
  }
  try {
    new MutationObserver(check).observe(document.documentElement, {
      childList: true,
      subtree: true
    });
  } catch (e) {}
  setInterval(check, 1000);
  check();
})();
