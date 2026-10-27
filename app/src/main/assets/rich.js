/*
 * Общий рендер текста: markdown (marked) + формулы KaTeX.
 * Поддерживает $...$, $$...$$, \(...\) и \[...\]. Работает без интернета.
 */
(function (global) {
  'use strict';

  var MATH_RE = /\$\$([\s\S]+?)\$\$|\\\[([\s\S]+?)\\\]|\\\(([\s\S]+?)\\\)|\$(?!\s)((?:\\\$|[^$\n])+?)\$(?!\d)/g;

  function escapeHtml(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  function renderTex(tex, display) {
    try {
      return katex.renderToString(tex, {
        displayMode: display,
        throwOnError: false,
        strict: 'ignore',
        trust: false,
        output: 'html'
      });
    } catch (e) {
      return '<code>' + escapeHtml(tex) + '</code>';
    }
  }

  /**
   * Заменяет формулы на метки, прогоняет markdown и возвращает формулы на место.
   * @param {string} text
   * @param {boolean} inline — без абзацев (для заголовков, ячеек таблиц, пунктов)
   */
  function renderRich(text, inline) {
    if (text == null) return '';
    var store = [];
    var src = String(text).replace(MATH_RE, function (m, d1, d2, i1, i2) {
      var display = d1 != null || d2 != null;
      var tex = d1 != null ? d1 : d2 != null ? d2 : i1 != null ? i1 : i2;
      store.push({ tex: tex.trim(), display: display });
      var token = 'KXPH' + (store.length - 1) + 'END';
      // Выключная формула — отдельным абзацем.
      return display && !inline ? '\n\n' + token + '\n\n' : token;
    });
    // Сырой HTML от модели не исполняем. «>» оставляем для цитат markdown.
    src = src.replace(/&/g, '&amp;').replace(/</g, '&lt;');
    var html;
    if (global.marked) {
      html = inline
        ? marked.parseInline(src, { gfm: true, breaks: true })
        : marked.parse(src, { gfm: true, breaks: true });
    } else {
      html = src.replace(/\n/g, '<br>');
    }
    html = html.replace(/<p>\s*KXPH(\d+)END\s*<\/p>/g, function (m, i) {
      var item = store[+i];
      return '<div class="math-block">' + renderTex(item.tex, true) + '</div>';
    });
    html = html.replace(/KXPH(\d+)END/g, function (m, i) {
      var item = store[+i];
      return renderTex(item.tex, item.display);
    });
    return html;
  }

  global.Rich = { render: renderRich, tex: renderTex, escape: escapeHtml };
})(window);
