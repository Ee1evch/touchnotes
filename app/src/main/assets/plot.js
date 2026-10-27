/* Графики функций для чистовика: SVG, выражения в синтаксисе math.js. */
(function (global) {
  'use strict';

  var W = 560, H = 330;
  var M = { l: 14, r: 14, t: 12, b: 14 };
  var COLORS = ['#3047c9', '#d9480f'];
  var mathjs = null;

  function lib() {
    if (!mathjs && global.math) {
      // predictable: sqrt(-1) и log(-1) дают NaN, а не комплексное число.
      mathjs = global.math.create(global.math.all, { predictable: true });
    }
    return mathjs;
  }

  function normalizeExpr(e) {
    return String(e)
      .replace(/[−–]/g, '-')
      .replace(/[·×]/g, '*')
      .replace(/\*\*/g, '^')
      .replace(/^\s*y\s*=\s*/i, '')
      .replace(/^\s*f\s*\(\s*x\s*\)\s*=\s*/i, '')
      .replace(/\bln\s*\(/g, 'log(')
      .replace(/\blg\s*\(/g, 'log10(')
      .replace(/\btg\s*\(/g, 'tan(')
      .replace(/\bctg\s*\(/g, 'cot(')
      .replace(/\barcsin\b/g, 'asin')
      .replace(/\barccos\b/g, 'acos')
      .replace(/\barctg\b|\barctan\b/g, 'atan');
  }

  function compile(expr) {
    var m = lib();
    if (!m) return null;
    try {
      var code = m.compile(normalizeExpr(expr));
      return function (x) {
        try {
          var v = code.evaluate({ x: x });
          if (typeof v === 'number') return v;
          if (v && typeof v.toNumber === 'function') return v.toNumber();
          if (v && typeof v.re === 'number') return Math.abs(v.im) < 1e-9 ? v.re : NaN;
          return NaN;
        } catch (e) {
          return NaN;
        }
      };
    } catch (e) {
      return null;
    }
  }

  function niceStep(span) {
    var raw = span / 7;
    var p = Math.pow(10, Math.floor(Math.log10(raw)));
    var f = raw / p;
    return (f < 1.5 ? 1 : f < 3 ? 2 : f < 7 ? 5 : 10) * p;
  }

  function fmt(v) {
    var r = Math.round(v * 1000) / 1000;
    return String(r).replace('-', '−').replace('.', ',');
  }

  function range(arr, fallback) {
    if (Array.isArray(arr) && arr.length >= 2 && isFinite(arr[0]) && isFinite(arr[1]) && arr[1] > arr[0]) {
      return [Number(arr[0]), Number(arr[1])];
    }
    return fallback;
  }

  function autoY(fns, xr, points) {
    var vals = [];
    fns.forEach(function (f) {
      for (var i = 0; i <= 200; i++) {
        var x = xr[0] + (xr[1] - xr[0]) * i / 200;
        var y = f(x);
        if (isFinite(y)) vals.push(y);
      }
    });
    (points || []).forEach(function (p) { if (isFinite(p.y)) vals.push(Number(p.y)); });
    if (!vals.length) return [-5, 5];
    vals.sort(function (a, b) { return a - b; });
    var lo = vals[Math.floor(vals.length * 0.04)];
    var hi = vals[Math.ceil(vals.length * 0.96) - 1];
    if (hi - lo < 1e-6) { lo -= 1; hi += 1; }
    var pad = (hi - lo) * 0.12;
    lo -= pad; hi += pad;
    if (lo > 0 && lo < (hi - lo) * 0.3) lo = -(hi - lo) * 0.06;
    if (hi < 0 && -hi < (hi - lo) * 0.3) hi = (hi - lo) * 0.06;
    return [lo, hi];
  }

  function stripTex(s) {
    return String(s || '').replace(/\$/g, '').replace(/\\/g, '');
  }

  function render(block) {
    var fns = [], labels = [];
    (block.functions || []).slice(0, 2).forEach(function (fd, i) {
      var f = compile(fd.expr || '');
      if (f) { fns.push({ f: f, color: COLORS[i % COLORS.length] }); labels.push({ text: fd.label || ('y = ' + fd.expr), color: COLORS[i % COLORS.length] }); }
    });
    var xr = range(block.x, [-5, 5]);
    var yr = range(block.y, null) || autoY(fns.map(function (o) { return o.f; }), xr, block.points);

    var pw = W - M.l - M.r, ph = H - M.t - M.b;
    function sx(x) { return M.l + (x - xr[0]) / (xr[1] - xr[0]) * pw; }
    function sy(y) { return M.t + (yr[1] - y) / (yr[1] - yr[0]) * ph; }

    var svg = [];
    var id = 'clip' + Math.floor(Math.random() * 1e9);
    svg.push('<svg xmlns="http://www.w3.org/2000/svg" width="' + W + '" height="' + H + '" viewBox="0 0 ' + W + ' ' + H + '" font-family="Roboto, Noto Sans, sans-serif">');
    svg.push('<defs><clipPath id="' + id + '"><rect x="' + M.l + '" y="' + M.t + '" width="' + pw + '" height="' + ph + '"/></clipPath>' +
      '<marker id="a' + id + '" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="#1d2233"/></marker></defs>');
    svg.push('<rect x="' + M.l + '" y="' + M.t + '" width="' + pw + '" height="' + ph + '" fill="#fff" stroke="#e3e7f0"/>');

    // Сетка
    var stepX = niceStep(xr[1] - xr[0]), stepY = niceStep(yr[1] - yr[0]);
    var axisY = (yr[0] <= 0 && yr[1] >= 0) ? sy(0) : sy(yr[0]);
    var axisX = (xr[0] <= 0 && xr[1] >= 0) ? sx(0) : sx(xr[0]);
    var x, y, ticks = [];
    for (x = Math.ceil(xr[0] / stepX) * stepX; x <= xr[1] + 1e-9; x += stepX) {
      svg.push('<line x1="' + sx(x) + '" y1="' + M.t + '" x2="' + sx(x) + '" y2="' + (M.t + ph) + '" stroke="#eef0f6"/>');
      if (Math.abs(x) > stepX / 2) ticks.push('<text x="' + sx(x) + '" y="' + Math.min(M.t + ph - 4, axisY + 15) + '" font-size="11" fill="#6a7189" text-anchor="middle">' + fmt(x) + '</text>');
    }
    for (y = Math.ceil(yr[0] / stepY) * stepY; y <= yr[1] + 1e-9; y += stepY) {
      svg.push('<line x1="' + M.l + '" y1="' + sy(y) + '" x2="' + (M.l + pw) + '" y2="' + sy(y) + '" stroke="#eef0f6"/>');
      if (Math.abs(y) > stepY / 2) ticks.push('<text x="' + Math.max(M.l + 4, axisX - 6) + '" y="' + (sy(y) + 4) + '" font-size="11" fill="#6a7189" text-anchor="' + (axisX - 6 < M.l + 20 ? 'start' : 'end') + '">' + fmt(y) + '</text>');
    }
    // Оси со стрелками
    svg.push('<line x1="' + M.l + '" y1="' + axisY + '" x2="' + (M.l + pw) + '" y2="' + axisY + '" stroke="#1d2233" stroke-width="1.3" marker-end="url(#a' + id + ')"/>');
    svg.push('<line x1="' + axisX + '" y1="' + (M.t + ph) + '" x2="' + axisX + '" y2="' + M.t + '" stroke="#1d2233" stroke-width="1.3" marker-end="url(#a' + id + ')"/>');
    svg.push('<text x="' + (M.l + pw - 6) + '" y="' + (axisY - 7) + '" font-size="13" font-style="italic" text-anchor="end">x</text>');
    svg.push('<text x="' + (axisX + 8) + '" y="' + (M.t + 12) + '" font-size="13" font-style="italic">y</text>');
    if (xr[0] <= 0 && xr[1] >= 0 && yr[0] <= 0 && yr[1] >= 0) {
      svg.push('<text x="' + (axisX - 5) + '" y="' + (axisY + 14) + '" font-size="11" fill="#6a7189" text-anchor="end">0</text>');
    }
    svg.push(ticks.join(''));

    // Кривые
    var N = 700, span = yr[1] - yr[0];
    fns.forEach(function (o) {
      var d = '', pen = false, prev = null;
      for (var i = 0; i <= N; i++) {
        var xv = xr[0] + (xr[1] - xr[0]) * i / N;
        var yv = o.f(xv);
        var ok = isFinite(yv) && yv > yr[0] - span * 20 && yv < yr[1] + span * 20;
        if (ok && prev !== null && Math.abs(yv - prev) > span * 1.5) ok = false; // разрыв (асимптота)
        if (!ok) { pen = false; prev = isFinite(yv) ? yv : null; continue; }
        d += (pen ? 'L' : 'M') + sx(xv).toFixed(2) + ',' + sy(yv).toFixed(2);
        pen = true;
        prev = yv;
      }
      if (d) svg.push('<path d="' + d + '" fill="none" stroke="' + o.color + '" stroke-width="2.4" stroke-linejoin="round" clip-path="url(#' + id + ')"/>');
    });

    // Точки
    (block.points || []).forEach(function (p) {
      if (!isFinite(p.x) || !isFinite(p.y)) return;
      var px = sx(Number(p.x)), py = sy(Number(p.y));
      if (px < M.l - 1 || px > M.l + pw + 1 || py < M.t - 1 || py > M.t + ph + 1) return;
      svg.push('<circle cx="' + px + '" cy="' + py + '" r="4.5" fill="#d23a3a" stroke="#fff" stroke-width="1.5"/>');
      if (p.label) svg.push('<text x="' + (px + 7) + '" y="' + (py - 7) + '" font-size="13" font-weight="700" fill="#d23a3a">' + Rich.escape(stripTex(p.label)) + '</text>');
    });
    svg.push('</svg>');

    var html = '<figure class="plot blk">' + svg.join('');
    if (labels.length) {
      html += '<div class="legend">' + labels.map(function (l) {
        return '<div class="item"><i class="swatch" style="background:' + l.color + '"></i>' + Rich.render(l.text, true) + '</div>';
      }).join('') + '</div>';
    }
    if (!fns.length && (block.functions || []).length) {
      html += '<figcaption>Не удалось построить: ' + Rich.escape((block.functions || []).map(function (f) { return f.expr; }).join('; ')) + '</figcaption>';
    }
    if (block.title) html += '<figcaption>' + Rich.render(block.title, true) + '</figcaption>';
    return html + '</figure>';
  }

  global.Plot = { render: render, compile: compile };
})(window);
