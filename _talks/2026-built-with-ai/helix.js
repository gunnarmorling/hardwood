// Helix for the "spiral" reveal: seen from above it is a ring the ball circles
// forever; the tilt fragment turns it on its side and the ball climbs.
// createHelix is taken unchanged from inputs/helix-reveal.html.

function createHelix(svg, { turns = 4, radius = 120, height = 240, speed = 0.6 } = {}) {
  const NS = 'http://www.w3.org/2000/svg';
  const el = (tag, attrs) => {
    const e = document.createElementNS(NS, tag);
    for (const k in attrs) e.setAttribute(k, attrs[k]);
    svg.appendChild(e);
    return e;
  };
  const axis  = el('line',   { class: 'axis', x1: 200, x2: 200, stroke: 'currentColor', 'stroke-dasharray': '4 5' });
  const back  = el('path',   { class: 'back', fill: 'none', stroke: 'currentColor', 'stroke-width': 2, 'stroke-linecap': 'round' });
  const ballB = el('circle', { class: 'ball', r: 14 });
  const front = el('path',   { fill: 'none', stroke: 'currentColor', 'stroke-width': 2.5, 'stroke-linecap': 'round' });
  const ballF = el('circle', { class: 'ball', r: 14 });

  const TAU = 2 * Math.PI, TMAX = turns * TAU, N = turns * 140, H = height, R = radius, FLAT = 0.035;
  let phi = 0, target = null, raf = null, last = 0;
  let mode = 'ring', a = 0, dir = 1, hd = 1, p0 = 0, s = 0;

  const proj = (ang, Y) => {
    const X = R * Math.cos(ang), Z = R * Math.sin(ang);
    return { x: 200 + X, y: 200 + Z * Math.cos(phi) - Y * Math.sin(phi), back: Z * Math.sin(phi) < -0.001 };
  };
  const wantClimb = () => (target ? target.to > 0 : phi > FLAT);

  function draw() {
    let fb = '', bk = '';
    const trace = (fn, n) => {
      let pf = false, pb = false;
      for (let i = 0; i <= n; i++) {
        const q = fn(i / n), c = q.x.toFixed(1) + ' ' + q.y.toFixed(1);
        if (q.back) { bk += (pb ? 'L' : 'M') + c; pb = true; pf = false; }
        else        { fb += (pf ? 'L' : 'M') + c; pf = true; pb = false; }
      }
    };
    trace(k => proj(k * TAU, -H / 2), 140);                      // base ring
    trace(k => proj(p0 + hd * k * TMAX, k * H - H / 2), N);      // spiral
    front.setAttribute('d', fb || 'M0 0');
    back.setAttribute('d', bk || 'M0 0');

    const sn = Math.sin(phi);
    axis.setAttribute('y1', 200 + (H / 2 + 30) * sn);
    axis.setAttribute('y2', 200 - (H / 2 + 30) * sn);
    axis.style.opacity = sn * 0.5;

    const q = mode === 'ring' ? proj(a, -H / 2) : proj(p0 + hd * s, s / TMAX * H - H / 2);
    for (const b of [ballF, ballB]) { b.setAttribute('cx', q.x); b.setAttribute('cy', q.y); }
    ballF.style.visibility = q.back ? 'hidden' : 'visible';
    ballB.style.visibility = q.back ? 'visible' : 'hidden';
  }

  function step(da) {
    const w = wantClimb();
    if (mode === 'ring') {
      const prev = a; a += dir * da;
      if (w && hd === dir) {
        const u = dir * (a - p0), up = dir * (prev - p0);
        if (Math.floor(u / TAU) !== Math.floor(up / TAU)) { s = u - TAU * Math.floor(u / TAU); mode = 'up'; }
      }
    } else if (mode === 'up')   { if (!w) { mode = 'down'; return; } s += da; if (s >= TMAX) { s = TMAX; mode = 'top'; } }
    else if (mode === 'top')    { if (!w) mode = 'down'; }
    else if (mode === 'down')   { if (w) { mode = 'up'; return; } s -= da; if (s <= 0) { a = p0 - hd * s; s = 0; dir = -hd; mode = 'ring'; } }
  }

  function frame(now) {
    const dt = Math.min(0.05, (now - last) / 1000); last = now;
    if (target) {
      const k = Math.min(1, (now - target.start) / 1800);
      const e = k < 0.5 ? 4 * k * k * k : 1 - Math.pow(-2 * k + 2, 3) / 2;
      phi = target.from + (target.to - target.from) * e;
      if (k >= 1) target = null;
    }
    step(dt * speed * TAU);
    draw();
    raf = requestAnimationFrame(frame);
  }

  function tiltTo(to) {
    if (to > 0 && mode === 'ring' && phi < FLAT) { p0 = a; hd = dir; s = 0; mode = 'up'; }
    target = { from: phi, to, start: performance.now() };
  }

  return {
    tilt:   () => tiltTo(Math.PI / 2),
    untilt: () => tiltTo(0),
    start:  () => { if (!raf) { last = performance.now(); raf = requestAnimationFrame(frame); } },
    stop:   () => { cancelAnimationFrame(raf); raf = null; },
    // Jump straight to a state, e.g. when navigating back into the slide
    snap(tilted) {
      target = null;
      if (tilted) { phi = Math.PI / 2; p0 = a; hd = dir; s = TMAX; mode = 'top'; }
      else        { phi = 0; s = 0; mode = 'ring'; }
      draw();
    },
  };
}

// ---- reveal.js wiring ----
// Slides come from Markdown, so they only exist once Reveal is ready.
// Grouped fragments (same data-fragment-index) fire one event, so check all of them.
(() => {
  const helixes = new Map();
  const hasTilt = (event) => (event.fragments || [event.fragment]).some(f => f && f.hasAttribute('data-helix-tilt'));

  function syncSlide(slide, previous) {
    if (previous && helixes.has(previous)) helixes.get(previous).stop();
    const h = helixes.get(slide);
    if (!h) return;
    const fragment = slide.querySelector('[data-helix-tilt]');
    const tilted = !!(fragment && fragment.classList.contains('visible'));
    slide.querySelector('svg[data-helix]').classList.toggle('tilted', tilted);
    h.snap(tilted);
    h.start();
  }

  function setTilt(event, tilted) {
    if (!hasTilt(event)) return;
    const slide = Reveal.getCurrentSlide();
    const h = helixes.get(slide);
    if (!h) return;
    slide.querySelector('svg[data-helix]').classList.toggle('tilted', tilted);
    tilted ? h.tilt() : h.untilt();
  }

  Reveal.on('ready', (event) => {
    document.querySelectorAll('svg[data-helix]').forEach((svg) => {
      const turns = Number(svg.dataset.turns || 4);
      const h = createHelix(svg, { turns });
      h.snap(false);
      helixes.set(svg.closest('section'), h);
    });
    syncSlide(event.currentSlide);
  });
  Reveal.on('slidechanged', (event) => syncSlide(event.currentSlide, event.previousSlide));
  Reveal.on('fragmentshown', (event) => setTilt(event, true));
  Reveal.on('fragmenthidden', (event) => setTilt(event, false));
})();
