// Какая сборка сейчас опубликована — страница спрашивает об этом GitHub, а не хранит ответ.
//
// Почему так, а не «положить APK рядом с сайтом»: подписанную сборку публикует человек
// (релизный поезд #306 — на чистом раннере keystore нет, и APK из CI подписан отладочным
// ключом, публиковать такое нельзя). Значит единственный источник правды о сборке — страница
// релизов, и лендинг обязан показывать её, а не свою копию.
//
// Не смогли спросить или релиза нет — кнопки ведут на страницу релизов, а строка под ними
// молчит: сборку не выдумываем и оправданиями человека не грузим.

(function () {
  'use strict';

  var REPO = 'librevlad/point';
  var box = document.querySelector('[data-build]');
  var buttons = Array.prototype.slice.call(document.querySelectorAll('a[data-get]'));

  function human(iso) {
    var d = new Date(iso);
    if (isNaN(d)) return '';
    return d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });
  }

  function mb(bytes) {
    return Math.round(bytes / 1024 / 1024) + ' MB';
  }

  function pick(assets, exts) {
    for (var i = 0; i < assets.length; i++) {
      var name = (assets[i].name || '').toLowerCase();
      for (var j = 0; j < exts.length; j++) {
        if (name.slice(-exts[j].length) === exts[j]) return assets[i];
      }
    }
    return null;
  }

  function setSize(a, asset) {
    var note = a.querySelector('small');
    if (note && asset && asset.size) note.textContent += ' · ' + mb(asset.size);
  }

  // Берём СПИСОК, а не /releases/latest: тот молча пропускает предварительные сборки.
  // Показываем самое свежее, где есть хоть одна сборка.
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
        return !r.draft && pick(r.assets || [], ['.apk', '.msi', '.exe']);
      })[0];
      if (!rel) return;

      var assets = rel.assets || [];
      var found = { android: pick(assets, ['.apk']), windows: pick(assets, ['.msi', '.exe']) };

      // Прямая ссылка на файл — только если он в релизе есть. Нет файла — ведём на релиз
      // целиком, а не на кнопку, которая скачает пустоту.
      buttons.forEach(function (a) {
        var asset = found[a.getAttribute('data-get')];
        if (asset && asset.browser_download_url) {
          a.href = asset.browser_download_url;
          setSize(a, asset);
        } else {
          a.href = rel.html_url;
        }
      });

      if (box) {
        var parts = ['Version ' + (rel.tag_name || '').replace(/^v/, '')];
        if (rel.published_at) parts.push(human(rel.published_at));
        box.innerHTML = parts.join(' · ') + ' · <a href="' + rel.html_url + '">what’s new</a>';
      }
    })
    .catch(function () { /* кнопки уже ведут на страницу релизов */ });
})();
