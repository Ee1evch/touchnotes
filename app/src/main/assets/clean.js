/* Вёрстка чистовика из JSON по листам A4. */
(function (global) {
  'use strict';

  var pagesEl = document.getElementById('pages');
  var staging = document.getElementById('staging');
  var errorEl = document.getElementById('error');
  var R = function (t) { return Rich.render(t, false); };
  var RI = function (t) { return Rich.render(t, true); };
  var esc = function (t) { return Rich.escape(t == null ? '' : t); };

  function node(html) {
    var d = document.createElement('div');
    d.innerHTML = html.trim();
    return d.firstChild;
  }

  function capitalize(s) {
    return s ? s.charAt(0).toUpperCase() + s.slice(1) : s;
  }

  function noteKind(text) {
    var t = String(text || '').trim();
    var m;
    if ((m = t.match(/^Проверь\s*[:：]\s*/i))) return { cls: 'check', label: 'Проверь', text: capitalize(t.slice(m[0].length)) };
    if ((m = t.match(/^Дополнение\s*[:：]\s*/i))) return { cls: 'extra', label: 'Дополнение', text: capitalize(t.slice(m[0].length)) };
    if ((m = t.match(/^(Важно|Замечание|Типичная ошибка)\s*[:：]\s*/i))) return { cls: '', label: m[1], text: capitalize(t.slice(m[0].length)) };
    return { cls: '', label: 'Важно', text: t };
  }

  function stepsHtml(title, items, start, cont) {
    return '<div class="steps blk" data-start="' + start + '">' +
      (title ? '<div class="title">' + RI(title) + (cont ? ' <span class="cont">(продолжение)</span>' : '') + '</div>' : '') +
      '<ol style="counter-reset: step ' + start + '">' + items.map(function (it) { return '<li>' + R(it) + '</li>'; }).join('') + '</ol></div>';
  }

  function blockHtml(b) {
    switch (b.type) {
      case 'h':
        return '<h2 class="blk">' + RI(b.text || b.title) + '</h2>';
      case 'p':
        return '<div class="blk">' + R(b.text) + '</div>';
      case 'def':
        return '<div class="box def blk"><div class="title">' + RI(b.title || 'Определение') + '</div>' + R(b.text) + '</div>';
      case 'thm':
        return '<div class="box thm blk"><div class="title">' + RI(b.title || 'Теорема') + '</div>' + R(b.text) + '</div>';
      case 'formula':
        return '<div class="formula blk">' + Rich.tex(b.tex || '', true) + '</div>';
      case 'steps':
        return stepsHtml(b.title || 'Решение', b.items || [], 0, false);
      case 'plot':
        return Plot.render(b);
      case 'table': {
        var head = (b.head || []).map(function (h) { return '<th>' + RI(h) + '</th>'; }).join('');
        var rows = (b.rows || []).map(function (r) {
          return '<tr>' + r.map(function (c) { return '<td>' + RI(c) + '</td>'; }).join('') + '</tr>';
        }).join('');
        return '<div class="blk"><table class="tbl">' + (head ? '<thead><tr>' + head + '</tr></thead>' : '') + '<tbody>' + rows + '</tbody></table></div>';
      }
      case 'note': {
        var k = noteKind(b.text);
        return '<div class="box note ' + k.cls + ' blk"><div class="label">' + esc(k.label) + '</div>' + R(k.text) + '</div>';
      }
      default:
        return b.text ? '<div class="blk">' + R(b.text) + '</div>' : '';
    }
  }

  function today() {
    var d = new Date();
    var p = function (n) { return (n < 10 ? '0' : '') + n; };
    return p(d.getDate()) + '.' + p(d.getMonth() + 1) + '.' + d.getFullYear();
  }

  function buildNodes(note) {
    var html = [];
    html.push('<div class="head blk"><div class="subject">' + esc(note.subject || '') + '</div><h1>' + RI(note.title || 'Конспект') +
      '</h1><div class="date">' + esc(note.date || today()) + '</div></div>');
    (note.blocks || []).forEach(function (b) {
      var h = blockHtml(b);
      if (h) html.push(h);
    });
    if ((note.summary || []).length) {
      html.push('<div class="summary blk"><div class="title">Коротко</div><ul>' +
        note.summary.map(function (s) { return '<li>' + RI(s) + '</li>'; }).join('') + '</ul></div>');
    }
    if ((note.questions || []).length) {
      html.push('<div class="questions blk"><div class="title">Проверь себя</div><ol>' +
        note.questions.map(function (s) { return '<li>' + RI(s) + '</li>'; }).join('') + '</ol></div>');
    }
    staging.innerHTML = '';
    var nodes = html.map(node).filter(Boolean);
    nodes.forEach(function (n) { staging.appendChild(n); });
    return nodes;
  }

  // ---------- разбивка на листы ----------

  var content = null;

  function newPage() {
    var p = document.createElement('div');
    p.className = 'page';
    p.innerHTML = '<div class="content"></div><div class="footer"><span class="f-left"></span><span class="f-right"></span></div>';
    pagesEl.appendChild(p);
    content = p.querySelector('.content');
    return content;
  }

  function overflows() {
    return content.scrollHeight > content.clientHeight + 1;
  }

  /** Делит длинное решение или таблицу: хвост уходит на следующий лист. */
  function splitToFit(el) {
    var isSteps = el.classList.contains('steps');
    var table = el.querySelector('table.tbl');
    var holder = isSteps ? el.querySelector('ol') : table ? table.querySelector('tbody') : null;
    if (!holder || holder.children.length < 2) return null;
    var moved = [];
    while (overflows() && holder.children.length > 1) {
      moved.unshift(holder.lastElementChild);
      holder.removeChild(holder.lastElementChild);
    }
    if (overflows() || !moved.length) {
      moved.forEach(function (m) { holder.appendChild(m); });
      return null;
    }
    var rest;
    if (isSteps) {
      var start = (+el.getAttribute('data-start') || 0) + holder.children.length;
      var titleEl = el.querySelector('.title');
      rest = node('<div class="steps blk" data-start="' + start + '">' +
        (titleEl ? '<div class="title">' + titleEl.innerHTML.replace(/ <span class="cont">.*<\/span>$/, '') + ' <span class="cont">(продолжение)</span></div>' : '') +
        '<ol style="counter-reset: step ' + start + '"></ol></div>');
      var ol = rest.querySelector('ol');
      moved.forEach(function (m) { ol.appendChild(m); });
    } else {
      rest = el.cloneNode(false);
      var t2 = table.cloneNode(false);
      var thead = table.querySelector('thead');
      if (thead) t2.appendChild(thead.cloneNode(true));
      var tb = document.createElement('tbody');
      moved.forEach(function (m) { tb.appendChild(m); });
      t2.appendChild(tb);
      rest.appendChild(t2);
    }
    return rest;
  }

  function place(el) {
    content.appendChild(el);
    if (!overflows()) return;
    var alone = content.children.length === 1;
    if (el.classList.contains('steps') || el.querySelector('table.tbl')) {
      var rest = splitToFit(el);
      if (rest) {
        newPage();
        place(rest);
        return;
      }
    }
    if (!alone) {
      content.removeChild(el);
      // Подзаголовок не оставляем в конце листа отдельно от текста.
      var last = content.lastElementChild;
      var carry = last && last.tagName === 'H2' && content.children.length > 1 ? last : null;
      if (carry) content.removeChild(carry);
      newPage();
      if (carry) content.appendChild(carry);
      place(el);
      return;
    }
    // Один блок выше листа — уменьшаем его.
    var k = content.clientHeight / el.scrollHeight;
    el.style.zoom = Math.max(0.3, k * 0.98);
  }

  function paginate(nodes) {
    pagesEl.innerHTML = '';
    newPage();
    nodes.forEach(place);
  }

  function footers(note) {
    var pages = pagesEl.querySelectorAll('.page');
    var plain = String(note.title || '').replace(/\$/g, '').replace(/\\[a-zA-Z]+/g, '').replace(/[{}]/g, '').replace(/\s+/g, ' ').trim();
    var left = [note.subject, plain].filter(Boolean).join(' · ');
    pages.forEach(function (p, i) {
      p.querySelector('.f-left').textContent = left;
      p.querySelector('.f-right').textContent = (i + 1) + ' / ' + pages.length;
    });
    return pages.length;
  }

  // Ширину окна сообщает приложение (Clean.fit): в окне поверх приложений window.innerWidth бывает неверным.
  var hostWidth = 0;

  function fitPreview() {
    if (!document.body.classList.contains('preview')) return;
    var w = hostWidth || window.innerWidth;
    // Пока WebView не разложен, ширина может быть 0 — тогда ждём следующего вызова.
    if (w < 50) return;
    var k = Math.min(1, Math.max(0.2, (w - 16) / 794));
    pagesEl.style.zoom = k;
  }

  function notify(fn, arg) {
    if (global.NativeClean && typeof global.NativeClean[fn] === 'function') global.NativeClean[fn](arg);
  }

  function nextFrame() {
    return new Promise(function (r) { requestAnimationFrame(function () { requestAnimationFrame(r); }); });
  }

  function render(note, mode) {
    errorEl.hidden = true;
    document.body.className = mode === 'export' ? 'export' : 'preview';
    pagesEl.style.zoom = 1;
    var nodes;
    try {
      nodes = buildNodes(note || {});
    } catch (e) {
      showError(e);
      return;
    }
    var fontsReady = document.fonts && document.fonts.ready ? document.fonts.ready : Promise.resolve();
    fontsReady.then(nextFrame).then(function () {
      try {
        paginate(nodes);
        staging.innerHTML = '';
        var n = footers(note || {});
        fitPreview();
        return nextFrame().then(function () { notify('onRendered', n); });
      } catch (e) {
        showError(e);
      }
    });
  }

  function showError(e) {
    errorEl.hidden = false;
    errorEl.textContent = 'Не удалось сверстать чистовик: ' + (e && e.message ? e.message : e);
    notify('onError', String(e && e.message ? e.message : e));
  }

  window.addEventListener('resize', fitPreview);
  global.Clean = {
    render: render,
    fit: function (w) {
      hostWidth = w;
      fitPreview();
    }
  };
  notify('onReady', '');
})(window);
