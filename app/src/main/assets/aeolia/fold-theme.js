// Fold theme: loads last and owns the look. Follows DESIGN-BRIEF.md: colours are sampled from the
// current Samsung scene (--scene-sky, --scene-ground, set by samsung-catalog.js); chrome is
// near-monochrome ink over it; one typeface (Space Grotesk) with real weight contrast; flat zones
// separated by hairline rules instead of cards; motion only on state change, settling fast.
(() => {
  // Metric tile shapes, as SVG masks that scale with the tile (the tiles are illustration, not chrome).
  const wobble = (n, amp, base = 44) => {
    let d = '';
    for (let i = 0; i <= 120; i++) {
      const t = i / 120 * Math.PI * 2, r = base + amp * Math.cos(n * t);
      d += (i ? 'L' : 'M') + (50 + r * Math.cos(t)).toFixed(2) + ',' + (50 + r * Math.sin(t)).toFixed(2);
    }
    return d + 'Z';
  };
  const SHAPES = {
    cookie: wobble(9, 4), clover: wobble(4, 7, 42), sun: wobble(12, 3.2, 45), flower: wobble(6, 5, 43),
    drop: 'M50,2 C50,2 92,44 92,62 A42,42 0 0 1 8,62 C8,44 50,2 50,2Z',
    arch: 'M3,97 V50 A47,47 0 0 1 97,50 V97 Z',
    gem: 'M50,3 L88,20 L97,58 L74,96 L26,96 L3,58 L12,20 Z',
    pill: 'M30,4 H70 A26,26 0 0 1 96,30 V70 A26,26 0 0 1 70,96 H30 A26,26 0 0 1 4,70 V30 A26,26 0 0 1 30,4 Z',
    hex: 'M50,3 L91,26 L91,74 L50,97 L9,74 L9,26 Z',
  };
  const mask = d => `url("data:image/svg+xml,${encodeURIComponent(`<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100' preserveAspectRatio='none'><path d='${d}'/></svg>`)}")`;
  // Each shape takes one of three scene-derived fills: sky, ground, or a blend.
  const FILL = { drop: 'sky', pill: 'blend', clover: 'ground', sun: 'blend', cookie: 'ground', gem: 'sky', arch: 'ground', flower: 'sky', hex: 'sky' };
  const shapeCss = Object.entries(SHAPES).map(([k, d]) =>
    `.metric[data-shape=${k}]{--tint:var(--tile-${FILL[k]})}.metric[data-shape=${k}]::before{-webkit-mask-image:${mask(d)};mask-image:${mask(d)}}`).join('\n');

  const css = document.createElement('style');
  css.textContent = `
  @font-face{font-family:"Space Grotesk";src:url(fonts/space_grotesk.ttf);font-weight:300 700}

  /* ---------- tokens. Surfaces are a dark (or light) tint of the scene's own sky. ---------- */
  :root{
    --scene-sky:#3f6f93;--scene-ground:#4f6a3a;
    --ink:#f4f1ea;--bg:color-mix(in srgb,var(--scene-sky) 12%,#0d0e0f);--bg2:color-mix(in srgb,var(--scene-sky) 16%,#131415);--bg3:color-mix(in srgb,var(--scene-sky) 24%,#1a1b1d);
    --ink2:color-mix(in srgb,var(--ink) 78%,var(--bg2));--ink3:color-mix(in srgb,var(--ink) 62%,var(--bg2));
    --line:color-mix(in srgb,var(--ink) 14%,transparent);--accent:var(--ink);--accent-ink:var(--ink);--warn:#ff8a70;--mixbase:#0d0b09;
    --tile-sky:color-mix(in srgb,var(--scene-sky) 52%,#0a0a0a);--tile-ground:color-mix(in srgb,var(--scene-ground) 52%,#0a0a0a);--tile-blend:color-mix(in srgb,color-mix(in srgb,var(--scene-sky),var(--scene-ground)) 46%,#0a0a0a);
    --rain:#8fc3dd;--snow:#d6e6f4;--inset-top:24px;--inset-bottom:0px;
    color-scheme:dark;font-family:"Space Grotesk",Roboto,sans-serif;color:var(--ink);background:var(--bg)}
  :root[data-theme=light]{
    --ink:#16171a;--bg:color-mix(in srgb,var(--scene-sky) 8%,#f7f6f3);--bg2:color-mix(in srgb,var(--scene-sky) 11%,#f1f0ec);--bg3:color-mix(in srgb,var(--scene-sky) 18%,#e8e6e1);
    --ink2:color-mix(in srgb,var(--ink) 80%,var(--bg2));--ink3:color-mix(in srgb,var(--ink) 66%,var(--bg2));--warn:#b23a22;--mixbase:#ffffff;
    --tile-sky:color-mix(in srgb,var(--scene-sky) 30%,#ffffff);--tile-ground:color-mix(in srgb,var(--scene-ground) 30%,#ffffff);--tile-blend:color-mix(in srgb,color-mix(in srgb,var(--scene-sky),var(--scene-ground)) 26%,#ffffff);
    --rain:#2b6a86;--snow:#4a6680;color-scheme:light}

  body{background:var(--bg);color:var(--ink);padding-bottom:var(--inset-bottom);transition:background-color .3s ease-out}
  h2{color:var(--ink);font-weight:700;letter-spacing:-.1px;font-size:17px;margin:0 0 16px}
  small,.card-subtitle,.empty,.radar-note,.metric small,.facts dt,.bar-scale,.radar-scale,.holiday-list small,.day-name small,.day-temp small,.hour small{color:var(--ink3)}

  /* ---------- hero: scene edge to edge; text over a soft scrim ---------- */
  main>.hero{height:clamp(460px,66vh,680px);padding:calc(var(--inset-top) + 124px) clamp(20px,4vw,48px) 0;border-radius:0}
  .hero::after{content:"";position:absolute;inset:0;z-index:-1;pointer-events:none;
    background:linear-gradient(180deg,rgba(8,8,8,.55) 0%,rgba(8,8,8,.26) 32%,rgba(8,8,8,.06) 58%,rgba(8,8,8,0) 74%,var(--bg) 100%),
               linear-gradient(90deg,rgba(8,8,8,.38) 0%,rgba(8,8,8,.1) 45%,rgba(8,8,8,0) 65%)}
  .hero,header,.location-tabs{text-shadow:0 1px 2px rgba(0,0,0,.55),0 2px 14px rgba(0,0,0,.35)}
  header svg{filter:drop-shadow(0 1px 2px rgba(0,0,0,.55))}
  header{top:var(--inset-top);padding-inline:clamp(20px,4vw,48px)}
  header h1{font-weight:700;font-size:20px;letter-spacing:-.2px}
  .localtime{display:none}
  .hero,.hero .degrees strong,.hero .condition,.hero .quip{color:#fff}
  .hero .degrees span,.hero .feels,.hero .stamp{color:#f1eee8}
  .degrees strong{font-weight:300;font-size:clamp(84px,12vw,140px);letter-spacing:-.055em;line-height:.95}
  .degrees span{font-size:clamp(28px,3.6vw,44px);font-weight:400}
  .condition{font-weight:600;font-size:clamp(20px,2.4vw,26px);margin:14px 0 6px;letter-spacing:-.1px}
  .hero .quip{font-weight:500;font-size:clamp(22px,2.6vw,26px);line-height:1.28;max-width:24ch;margin:4px 0 14px;cursor:pointer}
  .hero .quip[hidden]{display:none}
  .hero .feels{font-size:14px;line-height:1.5}
  .hero .stamp{font-size:12px;margin-top:4px;opacity:.85}
  .scene-note{left:clamp(20px,4vw,48px)!important;bottom:20px!important}

  /* ---------- places: plain text row, underline on the selected one, its time under it ---------- */
  .location-tabs{top:calc(var(--inset-top) + 64px)!important;max-width:none!important;padding-inline:clamp(20px,4vw,48px)!important;gap:20px!important;align-items:flex-start}
  .place-tab{display:flex!important;flex-direction:column;align-items:flex-start;gap:4px;background:none!important;border:0!important;border-radius:0!important;padding:4px 0!important;min-height:0!important;color:rgba(255,255,255,.62)!important}
  .place-name{font-size:13px;font-weight:700;letter-spacing:.09em;text-transform:uppercase;padding-bottom:5px;border-bottom:2px solid transparent}
  .place-tab[aria-current=page]{color:#fff!important}.place-tab[aria-current=page] .place-name{border-bottom-color:#fff}
  .place-time{display:none;font-size:12px;font-weight:400;letter-spacing:0}.place-tab[aria-current=page] .place-time{display:block}
  .place-tab[hidden]{display:none!important}

  /* ---------- sections: flat zones separated by full-width hairlines ---------- */
  main{gap:0!important;padding:0 clamp(20px,4vw,48px) 32px!important}
  main>.hero{margin:0 calc(-1*clamp(20px,4vw,48px))!important}
  main>.card:not(.hero){background:transparent;border-radius:0;padding:24px 0;border-top:1px solid var(--line)}
  @media(min-width:680px){main>.card[data-col="2"]:not(.hero){border-left:1px solid var(--line);padding-left:28px}main>.card[data-col="1"]:not(.hero){padding-right:28px}}
  .hour{color:var(--ink2)}.hour b{color:var(--ink)}
  .hour .hp{color:var(--rain)}.hour .hp.snow{color:var(--snow)}
  .wx-icon{color:var(--ink2)}
  .holiday-list li,.pollen-list li,.arrange-row,.results button,.ach-list li{border-color:var(--line)!important}
  .holiday-label{font-size:11px;letter-spacing:1.2px;text-transform:uppercase;color:var(--ink2);margin:14px 0 2px}
  .card-tools button{color:var(--ink3)}.card-tools button[aria-pressed=true]{color:var(--ink);border-bottom-color:var(--ink)}
  input[type=search]{background:var(--bg3);color:var(--ink);border-radius:10px}
  .search{background:var(--bg);padding-top:calc(var(--inset-top) + 16px)}
  body:has(.search.open) header{background:var(--bg2);color:var(--ink);text-shadow:none}
  body:has(.search.open) header button{color:var(--ink)}
  .saved-locations button,.tools-row,.tools-row button{color:var(--ink)!important}.saved-locations .remove{color:var(--ink3)!important}
  .notice{background:var(--ink);color:var(--bg);box-shadow:0 6px 20px rgba(0,0,0,.25)}
  button:hover{background:transparent}

  /* dialogs */
  .settings-sheet{background:var(--bg2)!important;color:var(--ink)!important;border-radius:20px!important}
  /* keep every sheet inside the visible area: below the status bar, above the nav bar, scrollable, closable */
  .settings-sheet{position:fixed!important;inset:0!important;margin:auto!important;box-sizing:border-box;width:min(520px,calc(100vw - var(--inset-left,0px) - var(--inset-right,0px) - 32px))!important;max-width:none!important;height:fit-content;
    max-height:calc(100dvh - var(--inset-top) - var(--inset-bottom) - 32px)!important;overflow-y:auto!important;overscroll-behavior:contain;z-index:100;padding:22px 22px calc(22px + env(safe-area-inset-bottom,0px))!important}
  .settings-sheet *{box-sizing:border-box;max-width:100%}.settings-sheet select{width:100%!important}
  .settings-sheet:not([open]){display:none!important}
  .settings-sheet>.close{position:sticky;top:0;float:right;z-index:1;min-width:44px;min-height:44px;font-size:22px;background:var(--bg3);color:var(--ink);border-radius:12px}
  .settings-sheet::backdrop{background:rgba(0,0,0,.55)!important}
  .settings-sheet p{color:var(--ink3)!important}
  .settings-sheet select{background:var(--bg3)!important;color:var(--ink)!important;border-radius:10px!important}
  .settings-sheet input{accent-color:var(--ink)!important}
  .settings-sheet>button:not(.close){display:block;width:100%;text-align:left;color:var(--ink);font-weight:600;padding:12px 0;border-top:1px solid var(--line);border-radius:0;margin:0}
  .settings-sheet .about{margin-top:18px;border-top:1px solid var(--line);padding-top:8px}.settings-sheet .about h3{font-size:13px;margin:10px 0 2px;color:var(--ink2)}.settings-sheet .about p{font-size:11px;line-height:1.6;margin:6px 0}
  .settings-sheet .nano-status{font-size:12px!important;color:var(--ink2)!important;margin:4px 0 0}
  .wake-time{margin-left:6px;background:var(--bg3);color:var(--ink);border:0;border-radius:8px;padding:4px 8px}

  /* ---------- metric tiles: scene-tinted shapes ---------- */
  ${shapeCss}
  .metric label,.metric small,.metric strong{color:#fff}
  :root[data-theme=light] .metric label,:root[data-theme=light] .metric small,:root[data-theme=light] .metric strong{color:#16171a}
  .metric[data-shape=drop]::before{background:linear-gradient(0deg,var(--fill,color-mix(in srgb,var(--rain) 45%,#0a0a0a)) var(--lvl),var(--tint) var(--lvl));animation:bw-fill .9s ease-out .15s both}
  :root[data-theme=light] .metric[data-shape=drop]{--fill:color-mix(in srgb,var(--rain) 30%,#ffffff)}
  .metric.wobble::before{animation:bw-press .22s ease-out}
  .metric[data-shape=drop].wobble::before{animation:none}

  /* ---------- 10-day: plain columns ---------- */
  #days{display:flex;gap:4px;overflow-x:auto;padding:2px 0 6px;scroll-snap-type:x proximity;scrollbar-width:none}
  #days::-webkit-scrollbar{display:none}
  #days .day{flex:0 0 76px;display:flex;flex-direction:column;align-items:center;gap:8px;padding:10px 4px;scroll-snap-align:start;text-align:center}
  #days .day-name{order:1;font-weight:700}#days .day .icon{order:2;margin:0}
  #days .day-temp:not(.low){order:3;font-size:17px}
  #days .temp-range{order:4;width:5px;height:46px;background:var(--line);border-radius:3px}
  #days .temp-range i{left:0;width:100%;bottom:var(--lo);height:max(6px,calc(var(--hi) - var(--lo)));background:linear-gradient(0deg,#7fb3c8,#e3a35a);border-radius:3px}
  #days .day-temp.low{order:5;color:var(--ink2)}

  /* ---------- alerts: plain rows; severity is uppercase text, not a coloured edge ---------- */
  .nws-status{color:var(--ink2)}
  .nws-alert{background:var(--bg3)!important;border:0!important;border-radius:12px!important}
  .alert-sev{display:block;font-size:11px;font-weight:700;letter-spacing:.1em;text-transform:uppercase;color:var(--ink2);margin-bottom:3px}
  .nws-alert[data-severity=Severe] .alert-sev,.nws-alert[data-severity=Extreme] .alert-sev{color:var(--warn)}

  /* ---------- radar ---------- */
  .radar-map{background:var(--bg3);border-radius:12px;margin:0!important}
  .radar-zoom button{background:rgba(14,14,14,.78);color:#fff;border-radius:10px}
  .radar-stamp{background:rgba(14,14,14,.78)!important;border-radius:8px!important}.radar-stamp b{color:rgba(255,255,255,.75)!important}
  .radar-msg{background:var(--bg2);color:var(--ink)}
  .radar-pin{background:#fff}
  .radar-bar button{background:var(--ink);color:var(--bg);border-radius:50%}
  .radar-track input{accent-color:var(--ink)}
  .radar-scale span+span{border-color:var(--line)}
  .storm-eta{background:none!important;border-radius:0!important;padding:14px 0 0!important;border-top:1px solid var(--line)}
  .storm-eta i{font-style:normal!important;font-family:inherit!important;font-weight:500}

  /* small cards */
  .bars i{background:var(--rain)}
  .moon svg{color:var(--ink)}
  .facts dd{color:var(--ink)}
  :root[data-theme=light] .hour .huv,:root[data-theme=light] .uv-head strong{filter:brightness(.6) saturate(1.4)}
  .secret-line{font-size:20px!important;font-weight:500;line-height:1.35!important;color:var(--ink)!important}

  /* ---------- motion: on state change only; fast, no overshoot ---------- */
  @property --lvl{syntax:'<percentage>';inherits:false;initial-value:0%}
  @keyframes bw-fill{from{--lvl:0%}to{--lvl:var(--level)}}
  @keyframes bw-in{from{opacity:0;transform:translateY(8px)}to{opacity:1;transform:none}}
  @keyframes bw-press{0%{transform:scale(1)}40%{transform:scale(.95)}100%{transform:scale(1)}}
  @keyframes bw-grow{from{transform:scaleY(0)}to{transform:scaleY(1)}}
  @keyframes bw-hop{0%{transform:none}40%{transform:translateY(-14px)}100%{transform:none}}
  main.entering>.card:not(.hero){animation:bw-in .25s ease-out both;animation-delay:calc(min(var(--i,0),6)*30ms)}
  #days .temp-range i{transform-origin:bottom;animation:bw-grow .3s ease-out both}
  .hero-char.hop{animation:bw-hop .45s cubic-bezier(.25,.6,.3,1)}
  .radar-map .wx>div{visibility:visible!important;opacity:0;transition:opacity .3s ease-out}.radar-map .wx>div.on{opacity:1}
  .hero-text.slide-l{animation:bw-slide-l .25s ease-out}.hero-text.slide-r{animation:bw-slide-r .25s ease-out}
  @keyframes bw-slide-l{from{opacity:.2;transform:translateX(28px)}to{opacity:1;transform:none}}
  @keyframes bw-slide-r{from{opacity:.2;transform:translateX(-28px)}to{opacity:1;transform:none}}

  /* pull to refresh: the sun turns, then settles */
  .pull{position:fixed;top:calc(var(--inset-top) + 8px);left:50%;width:40px;height:40px;z-index:70;opacity:0;transform:translate(-50%,0);pointer-events:none;border-radius:50%;background:var(--ink);display:grid;place-items:center}
  .pull svg{width:24px;height:24px;fill:none;stroke:var(--bg);stroke-width:2;stroke-linecap:round}
  .pull.spinning{opacity:1!important;animation:bw-pullspin .8s linear infinite}
  @keyframes bw-pullspin{from{transform:translate(-50%,40px) rotate(0)}to{transform:translate(-50%,40px) rotate(360deg)}}

  /* weather moments: rain on the screen edge, frost at 0 C */
  .fx{position:fixed;inset:0;pointer-events:none;z-index:40;overflow:hidden}
  .edge-drop{position:absolute;top:-20px;width:6px;height:10px;border-radius:50% 50% 50% 50%/60% 60% 40% 40%;background:radial-gradient(circle at 35% 35%,rgba(255,255,255,.9),rgba(160,210,235,.55) 60%,rgba(160,210,235,.2));animation:bw-drip 4s cubic-bezier(.55,0,.8,.6) infinite}
  @keyframes bw-drip{0%{transform:translateY(0)}20%{transform:translateY(12vh)}30%{transform:translateY(14vh)}100%{transform:translateY(110vh)}}
  .confetti{display:none}
  body.frosty::after{content:"";position:fixed;inset:0;pointer-events:none;z-index:39;
    background:radial-gradient(120px 120px at 0 0,rgba(225,238,248,.5),rgba(225,238,248,0) 70%),radial-gradient(120px 120px at 100% 0,rgba(225,238,248,.45),rgba(225,238,248,0) 70%),
      radial-gradient(140px 140px at 0 100%,rgba(225,238,248,.4),rgba(225,238,248,0) 70%),radial-gradient(140px 140px at 100% 100%,rgba(225,238,248,.45),rgba(225,238,248,0) 70%)}

  /* app, not web page */
  body{-webkit-user-select:none;user-select:none;-webkit-touch-callout:none;-webkit-tap-highlight-color:transparent}
  input,textarea{-webkit-user-select:text;user-select:text}
  body::before{content:"";position:fixed;inset:0 0 auto 0;height:calc(var(--inset-top) + 14px);z-index:50;pointer-events:none;background:linear-gradient(rgba(8,8,8,.65) 50%,transparent)}
  #widgetPreview,.widget-promo{display:none}
  @media(prefers-reduced-motion:reduce){main.entering>*,.metric::before,#days .temp-range i,.hero-char.hop,.hero-text{animation:none!important}.metric[data-shape=drop]::before{--lvl:var(--level)}.radar-map .wx>div{transition:none}.fx{display:none}}
  `;
  document.head.append(css);

  // Java sends the phone's insets and dark/light; see MainActivity.applyChrome.
  window.applyChrome = (o) => {
    const r = document.documentElement;
    r.style.setProperty('--inset-top', o.top + 'px');
    r.style.setProperty('--inset-bottom', o.bottom + 'px');
    r.dataset.theme = o.dark ? 'dark' : 'light';
  };
})();
