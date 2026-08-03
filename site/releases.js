// Какая сборка сейчас опубликована — страница спрашивает об этом GitHub, а не хранит ответ.
//
// Почему так, а не «положить APK рядом с сайтом»: подписанную сборку публикует человек
// (релизный поезд #306 — на чистом раннере keystore нет, и APK из CI подписан отладочным
// ключом, публиковать такое нельзя). Значит единственный источник правды о сборке — страница
// релизов, и лендинг обязан показывать её, а не свою копию.
//
// Ошибка сети или отсутствие релиза не выдумывают сборку: страница честно говорит, что
// опубликованной сборки нет, и ведёт к исходникам.

(function () {
  'use strict';

  var REPO = 'librevlad/point';
  var box = document.querySelector('[data-build]');
  if (!box) return;

  var LINKS = Array.prototype.slice.call(
    document.querySelectorAll('a[href*="/releases/latest"]')
  );

  function say(html) { box.innerHTML = html; }

  function human(iso) {
    var d = new Date(iso);
    if (isNaN(d)) return '';
    return d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' });
  }

  function mb(bytes) {
    return (bytes / 1024 / 1024).toFixed(1) + ' MB';
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

  // Берём СПИСОК, а не /releases/latest: тот молча пропускает предварительные сборки, а «своя
  // сборка» владельца — как раз предварительная. Показываем самое свежее, что есть.
  fetch('https://api.github.com/repos/' + REPO + '/releases?per_page=10', {
    headers: { Accept: 'application/vnd.github+json' }
  })
    .then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.json();
    })
    .then(function (list) {
      if (!Array.isArray(list) || list.length === 0) return null;
      return list.filter(function (rel) { return !rel.draft; })[0] || null;
    })
    .then(function (rel) {
      if (!rel) {
        say('No published build yet — Point is built from source. ' +
          '<a href="https://github.com/' + REPO + '#readme">How to build</a>.');
        return;
      }

      var assets = rel.assets || [];
      var apk = pick(assets, ['.apk']);
      var win = pick(assets, ['.msi', '.exe']);

      // Прямая ссылка на файл — только если он в релизе есть. Нет файла — ведём на релиз
      // целиком, а не на кнопку, которая скачает пустоту.
      LINKS.forEach(function (a) {
        var wantsWindows = /EXE for Windows/i.test(a.textContent || '');
        var asset = wantsWindows ? win : apk;
        if (asset && asset.browser_download_url) a.href = asset.browser_download_url;
        else a.href = rel.html_url;
      });

      var parts = ['<strong style="color:#A1A6B3;font-weight:600">' + (rel.tag_name || 'latest') + '</strong>'];
      // Предварительная сборка называется своим именем: человек должен знать, что берёт
      // свежее и непроверенное, а не выпущенную версию.
      if (rel.prerelease) parts.push('своя сборка');
      if (rel.published_at) parts.push('published ' + human(rel.published_at));
      if (apk) parts.push('APK ' + mb(apk.size));
      if (!apk) parts.push('no APK in this release');
      if (win) parts.push('Windows ' + mb(win.size));

      say(parts.join(' · ') + ' · <a href="' + rel.html_url + '">all releases</a>');
    })
    .catch(function () {
      // Молчаливого «всё хорошо» здесь быть не должно: не смогли спросить — так и говорим.
      say('Could not reach GitHub for the build info — ' +
        '<a href="https://github.com/' + REPO + '/releases/latest">open the releases page</a>.');
    });
})();
