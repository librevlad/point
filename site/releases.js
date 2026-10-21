// Какая сборка сейчас опубликована — страница спрашивает об этом GitHub, а не хранит ответ.
//
// Подписанную сборку публикует человек (#306), поэтому единственный источник правды о ней —
// страница релизов. Кнопки ведут на файл последнего опубликованного релиза; под кнопкой —
// версия и размер. Не смогли спросить GitHub или файла нет — кнопка остаётся ссылкой на
// страницу релизов, а подпись пустой: размер, которого мы не знаем, не выдумывается.

(function () {
  'use strict';

  var REPO = 'librevlad/point';

  function mb(bytes) {
    return Math.round(bytes / 1024 / 1024) + ' MB';
  }

  function pick(assets, ext) {
    for (var i = 0; i < assets.length; i++) {
      var name = (assets[i].name || '').toLowerCase();
      if (name.slice(-ext.length) === ext) return assets[i];
    }
    return null;
  }

  function each(selector, fn) {
    Array.prototype.forEach.call(document.querySelectorAll(selector), fn);
  }

  // Берём СПИСОК, а не /releases/latest: тот молча пропускает предварительные сборки.
  // Показываем самый свежий неначерновой релиз, в котором есть хоть одна сборка.
  fetch('https://api.github.com/repos/' + REPO + '/releases?per_page=10', {
    headers: { Accept: 'application/vnd.github+json' }
  })
    .then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.json();
    })
    .then(function (list) {
      if (!Array.isArray(list)) return;
      var rel = list.filter(function (r) {
        return !r.draft && (pick(r.assets || [], '.apk') || pick(r.assets || [], '.msi'));
      })[0];
      if (!rel) return;

      var files = { apk: pick(rel.assets, '.apk'), msi: pick(rel.assets, '.msi') };
      var version = rel.tag_name || '';

      each('[data-dl]', function (a) {
        var f = files[a.getAttribute('data-dl')];
        a.href = f ? f.browser_download_url : rel.html_url;
      });
      each('[data-size]', function (s) {
        var f = files[s.getAttribute('data-size')];
        if (f) s.textContent = version + ' · ' + mb(f.size);
      });
    })
    .catch(function () { /* кнопки уже ведут на страницу релизов */ });
})();
