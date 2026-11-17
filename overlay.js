(async () => {
  const $ = (s) => document.querySelector(s);
  addEventListener('error', (e) => api.log('캐릭터 화면 오류: ' + e.message + ' (줄 ' + e.lineno + ')'));
  addEventListener('unhandledrejection', (e) => api.log('캐릭터 화면 오류: ' + (e.reason && e.reason.message || e.reason)));
  let cfg = await api.getConfig();
  const pet = $('#pet'), face = $('#face'), bub = $('#bubble'), menu = $('#menu');
  const bar = $('#bar'), vl = $('#vl'), vr = $('#vr'), catcher = $('#catcher');

  const EM = { a: '😺', b: '😸', d: '😲', e: '😿', i: '😴', g: '😮' };
  const HA = ['{u}님, 많이 바쁘신가요?', '쉬엄쉬엄 하세요~', '혹시 요즘 고민이 있다면 저에게 말씀해주세요.', '오늘도 힘내세요 {u}님.', '제가 곁에 있을게요 {u}님.'];
  const HB = ['{u}, 나 심심해', '천천히 해~', '요즘 고민되는거 있어?', '오늘도 힘내!', '내가 옆에 있어줄게'];
  const HINT = [10000, 20000, 60000, 180000];
  const IGN = new Set(['', 'explorer', 'searchhost', 'shellexperiencehost', 'lockapp', 'startmenuexperiencehost', 'applicationframehost', 'electron', 'textinputhost']);

  // ---------- 상태 ----------
  let busy = false, idle = false, drag = false, over = false, side = 0, gone = false;
  let parked = !!cfg.parked && cfg.walkOn;
  let flipped = !!cfg.flipped && cfg.walkOn && cfg.faceKeep;
  let walking = false, menuOpen = false, held = false, capState = false;
  let tm = [], idleT = 0, hT = 0, walkT = 0, tapT = 0, longT = 0, raf = 0;
  let px = 0, py = 0, gx = 0, gy = 0, sx = 0, sy = 0, moved = false, longed = false;
  let curKey = null, curV = -1, serial = 0, notifTarget = null;
  const geo = {};

  const U = () => cfg.user || '사용자';
  const pick = (a, b) => (cfg.hon ? a : b);
  const S = () => cfg.sizePx;
  const W = () => innerWidth, H = () => innerHeight;
  const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
  const later = (ms, fn) => tm.push(setTimeout(fn, ms));
  const dtxt = () => pick(`${U()}님이 원하신다면..`, `${U()}..진심이야?`);

  // ---------- 위치 / 말풍선 ----------
  function above(el) {
    const w = el.offsetWidth, h = el.offsetHeight, s = S();
    let x = clamp(px + s / 2 - w / 2, 4, Math.max(4, W() - w - 4));
    let y = py - h - 6;
    if (y < 4) y = py + s + 6;
    el.style.left = x + 'px';
    el.style.top = y + 'px';
  }
  function place(x, y) {
    const s = S();
    px = clamp(x, 0, Math.max(0, W() - s));
    py = clamp(y, 0, Math.max(0, H() - s));
    pet.style.left = px + 'px';
    pet.style.top = py + 'px';
    if (bub.style.display === 'block') above(bub);
  }
  function applySize() { pet.style.setProperty('--s', S() + 'px'); }
  function savePos() { api.setConfig({ posX: px, posY: py }); }
  function showB(t) {
    if (Array.isArray(t)) {
      bub.textContent = '';
      bub.append(t[0]);
      const sp = document.createElement('span');
      sp.className = 'app';
      sp.textContent = t[1];
      bub.append(sp, t[2]);
    } else bub.textContent = t;
    bub.style.display = 'block';
    bub.style.fontSize = cfg.bubbleSp + 'px';
    const a = cfg.bubbleAlpha / 100;
    bub.style.background = `rgba(255,255,255,${a})`;
    bub.style.borderColor = `rgba(85,85,85,${a})`;
    above(bub);
  }
  const hideB = () => { bub.style.display = 'none'; };

  // ---------- 이미지 ----------
  function keyFor(k) { return (k === 'w' || k === 'g') && !cfg.images[k] ? 'a' : k; }
  function loopMs(k) { const im = cfg.images[keyFor(k)]; return im ? im.loopMs || 0 : 0; }
  function setImage(key) {
    const k = keyFor(key);
    const im = cfg.images[k];
    const v = im ? im.v : 0;
    if (k === curKey && v === curV) return;
    curKey = k; curV = v;
    face.textContent = '';
    let url = null;
    if (im && im.file) { try { url = api.fileUrl(im.file); } catch (e) { url = null; } }
    if (url) {
      const img = new Image();
      img.draggable = false;
      img.onerror = () => { api.log('이미지를 불러오지 못했어요: ' + im.file); face.textContent = EM[k]; };
      img.src = url;
      face.appendChild(img);
    } else {
      face.textContent = EM[k];
    }
  }
  const stateImage = () => (drag ? 'd' : idle ? 'i' : 'a');

  // ---------- 좌우 방향 ----------
  function applyFlip() { face.style.setProperty('--flip', flipped ? -1 : 1); }
  function setFlip(f) { flipped = f; applyFlip(); api.setConfig({ flipped: f }); }
  function endFacing() {
    if (!cfg.faceKeep) flipped = false;
    applyFlip();
    api.setConfig({ flipped });
  }

  // ---------- 효과 ----------
  function fx(n, once) {
    pet.className = '';
    if (!n) return;
    void pet.offsetWidth;
    const k = [1.6, 1, 0.6][cfg.fxSpeed] || 1;
    pet.style.setProperty('--k', k);
    pet.style.setProperty('--it', once || !cfg.fxRepeat ? 2 : 'infinite');
    pet.classList.add('fx' + n);
  }

  // ---------- 마우스 통과 제어 ----------
  function capture(on) {
    if (on !== capState) { capState = on; api.setMouse(on); }
  }
  addEventListener('mousemove', (e) => {
    if (held || menuOpen) return;
    capture(!!(e.target.closest && e.target.closest('[data-hit]')));
  });

  // ---------- 이벤트 공통 ----------
  function cancel() {
    tm.forEach(clearTimeout); tm = []; serial++; notifTarget = null;
    stopWalk(); closeMenu(); hideB(); fx(null);
  }
  // 새 이벤트는 이전 이미지/말풍선을 즉시 교체. 이미지가 더 길면 말풍선 시간을 늘림
  function show(key, text, baseMs, effect, once, count) {
    cancel();
    busy = true;
    clearTimeout(hT);
    if (count !== false) resetIdle();
    setImage(key);
    if (text) showB(text);
    fx(effect, once);
    later(Math.max(baseMs, loopMs(key)), toA);
  }
  function toA() {
    tm.forEach(clearTimeout); tm = []; notifTarget = null;
    stopWalk(); closeMenu(); hideB();
    busy = false;
    if (drag) { setImage('d'); fx(cfg.fx.d); if (over) showB(dtxt()); }
    else { fx(null); setImage(idle ? 'i' : 'a'); }
    sched(); schedWalk();
  }
  function resetIdle() {
    idle = false;
    clearTimeout(idleT);
    idleT = setTimeout(goIdle, cfg.hOn ? 300000 : 30000);
  }
  function goIdle() {
    idle = true;
    stopWalk();
    if (!busy && !drag) setImage('i');
  }

  // ---------- h: 혼잣말 ----------
  function sched() {
    clearTimeout(hT);
    if (!cfg.hOn) return;
    hT = setTimeout(() => {
      if (idle || gone) return;
      if (busy || drag) { sched(); return; }
      const i = Math.floor(Math.random() * 5);
      show('a', pick(HA[i], HB[i]).replace('{u}', U()), 3000, 0, false, false);
    }, HINT[cfg.hIdx] || 60000);
  }

  // ---------- 이동 ----------
  function schedWalk() {
    clearTimeout(walkT);
    if (!cfg.walkOn || parked || idle || gone) return;
    const wait = cfg.walkMode === 1 ? 5000 + Math.random() * 10000 : cfg.walkWait * 1000;
    walkT = setTimeout(startWalk, wait);
  }
  function startWalk() {
    if (!cfg.walkOn || parked || busy || drag || idle || walking || gone) return;
    const s = S(), bottomY = Math.max(0, H() - s - cfg.barPx - 8);
    const tx = Math.random() * Math.max(1, W() - s);
    const ty = cfg.walkRange === 1 ? bottomY : cfg.walkRange === 2 ? py : Math.random() * Math.max(1, bottomY);
    const dx = tx - px, dy = ty - py, dist = Math.hypot(dx, dy);
    if (dist < 20) { schedWalk(); return; }
    const speed = [50, 100, 170][cfg.walkSpeed] || 100;
    const dur = Math.max(300, (dist / speed) * 1000);
    if (Math.abs(dx) > 4) setFlip((cfg.walkFace === 0) === (dx < 0));
    walking = true;
    setImage('w');
    const x0 = px, y0 = py, t0 = performance.now();
    const step = (t) => {
      if (!walking) return;
      const f = Math.min(1, (t - t0) / dur);
      place(x0 + dx * f, y0 + dy * f);
      if (f >= 1) {
        walking = false;
        endFacing();
        setImage(idle ? 'i' : 'a');
        savePos();
        schedWalk();
        return;
      }
      raf = requestAnimationFrame(step);
    };
    raf = requestAnimationFrame(step);
  }
  function stopWalk() {
    clearTimeout(walkT);
    if (walking) {
      walking = false;
      cancelAnimationFrame(raf);
      endFacing();
      setImage(idle ? 'i' : 'a');
      savePos();
    }
  }
  function setParked(v) { parked = v; api.setConfig({ parked: v }); }

  // ---------- 터치: b(클릭) / c(더블클릭) / d(꾹 누르기) ----------
  pet.addEventListener('pointerdown', (e) => {
    if (gone || e.button !== 0) return;
    stopWalk();
    held = true; capture(true);
    gx = e.clientX - px; gy = e.clientY - py;
    sx = e.clientX; sy = e.clientY;
    moved = false; longed = false;
    pet.setPointerCapture(e.pointerId);
    longT = setTimeout(() => { longed = true; startDrag(); }, cfg.longMs);
  });
  pet.addEventListener('pointermove', (e) => {
    if (longed) { place(e.clientX - gx, e.clientY - gy); barState(e.clientX, e.clientY); }
    else if (!moved && Math.hypot(e.clientX - sx, e.clientY - sy) > 6) { moved = true; clearTimeout(longT); }
  });
  function up(e) {
    clearTimeout(longT);
    held = false;
    if (longed) { barState(e.clientX, e.clientY); longed = false; endDrag(); }
    else if (!moved && e.type === 'pointerup') onTap();
    else toA();
    const t = document.elementFromPoint(e.clientX, e.clientY);
    capture(!!(t && t.closest && t.closest('[data-hit]')) || menuOpen);
  }
  pet.addEventListener('pointerup', up);
  pet.addEventListener('pointercancel', up);
  pet.addEventListener('contextmenu', (e) => { e.preventDefault(); api.openSettings(); });

  function onTap() {
    if (tapT) { clearTimeout(tapT); tapT = 0; dbl(); }
    else tapT = setTimeout(() => { tapT = 0; single(); }, 300);
  }
  function single() {
    if (notifTarget) api.openNotif(notifTarget);
    show('b', null, Math.max(loopMs('b'), 500), cfg.fx.b);
  }
  function dbl() {
    cancel(); busy = true; clearTimeout(hT); resetIdle();
    setImage('a'); fx(cfg.fx.c, true);
    openMenu();
  }

  // ---------- d: 꾹 눌러 들기 ----------
  function startDrag() {
    cancel(); clearTimeout(hT); resetIdle();
    drag = true; over = false; side = 0; busy = true;
    setImage('d'); fx(cfg.fx.d);
    bar.style.height = cfg.barPx + 'px'; bar.className = ''; bar.style.display = 'block';
    if (cfg.walkOn) {
      for (const v of [vl, vr]) { v.style.width = cfg.barPx + 'px'; v.style.bottom = cfg.barPx + 'px'; v.className = 'vb'; v.style.display = 'block'; }
    }
  }
  function barState(x, y) {
    const o = y >= H() - cfg.barPx;
    const sd = !o && cfg.walkOn ? (x <= cfg.barPx ? 1 : x >= W() - cfg.barPx ? 2 : 0) : 0;
    if (o !== over) {
      over = o;
      bar.classList.toggle('red', o);
      if (o) showB(dtxt()); else hideB();
    }
    if (sd !== side) {
      side = sd;
      vl.classList.toggle('on', sd === 1);
      vr.classList.toggle('on', sd === 2);
    }
  }
  function endDrag() {
    drag = false;
    const exitNow = over, sd = side;
    bar.style.display = vl.style.display = vr.style.display = 'none';
    over = false; side = 0;
    if (exitNow) {
      hideB(); fx(null); gone = true; busy = false;
      pet.style.display = 'none';
      api.exitPet();
      return;
    }
    if (sd && cfg.walkOn) {
      setParked(true);
      place(sd === 1 ? 0 : W() - S(), py);
    } else {
      setParked(false);
    }
    cancel(); busy = false; toA(); savePos();
  }

  // ---------- c: 메뉴 ----------
  function mkBtn(emoji, label, fn, iconPath) {
    const b = document.createElement('button');
    b.dataset.hit = '1';
    const ic = document.createElement('span'); ic.className = 'ic'; ic.textContent = emoji;
    const lb = document.createElement('span'); lb.className = 'lb'; lb.textContent = label;
    b.append(ic, lb);
    b.onclick = () => { closeMenu(); fn(); };
    if (iconPath) api.appIcon(iconPath).then((u) => {
      if (u) { ic.textContent = ''; const im = new Image(); im.src = u; ic.appendChild(im); }
    });
    return b;
  }
  function appBtn(i) {
    const a = cfg.apps[i];
    if (!a) return mkBtn('➕', '앱 선택', () => show('a', '설정에서 바로가기 앱을 골라 주세요', 3000, 0));
    return mkBtn('📱', a.name, () => { api.launch(a.path); toA(); }, a.path);
  }
  function openMenu() {
    menu.textContent = '';
    const items = cfg.menuMode === 1
      ? [mkBtn('☀️', '날씨', weather), appBtn(0), mkBtn('🎲', 'O/X 룰렛', roulette)]
      : [appBtn(0), appBtn(1), appBtn(2)];
    items.forEach((b) => menu.appendChild(b));
    menu.style.display = 'flex';
    menuOpen = true;
    catcher.style.display = 'block';
    capture(true);
    above(menu);
  }
  function closeMenu() {
    if (!menuOpen) return;
    menu.style.display = 'none';
    catcher.style.display = 'none';
    menuOpen = false;
    if (!held) capture(false);
  }
  catcher.addEventListener('pointerdown', () => { closeMenu(); toA(); });

  function roulette() {
    const r = Math.random() < 0.5 ? 'O' : 'X';
    show('a', '3', 60000, 0);
    later(1000, () => showB('2'));
    later(2000, () => showB('1'));
    later(3000, () => showB(pick(`결과는 ${r} 입니다 ${U()}님`, `결과는 ${r} 이야 ${U()}!`)));
    later(6000, toA);
  }
  function wtxt(c) {
    if (c === 0) return '맑음';
    if (c === 1 || c === 2) return '구름 조금';
    if (c === 3) return '흐림';
    if (c === 45 || c === 48) return '안개';
    if (c >= 51 && c <= 57) return '이슬비';
    if (c >= 61 && c <= 67) return '비';
    if (c >= 71 && c <= 77) return '눈';
    if (c >= 80 && c <= 82) return '소나기';
    if (c === 85 || c === 86) return '눈 소나기';
    if (c >= 95 && c <= 99) return '천둥번개';
    return '알 수 없음';
  }
  async function weather() {
    show('a', pick('날씨를 확인하고 있어요...', '날씨 확인 중...'), 15000, 0);
    const ser = serial;
    let msg;
    try {
      const city = cfg.city || '서울';
      if (!geo[city]) {
        const g = await (await fetch('https://geocoding-api.open-meteo.com/v1/search?name=' + encodeURIComponent(city) + '&count=1&language=ko')).json();
        geo[city] = g.results[0];
      }
      const { latitude, longitude } = geo[city];
      const w = (await (await fetch(`https://api.open-meteo.com/v1/forecast?latitude=${latitude}&longitude=${longitude}&current_weather=true`)).json()).current_weather;
      const t = Math.round(w.temperature), d = wtxt(w.weathercode);
      msg = pick(`${city} 현재 ${t}°C, ${d} 입니다.`, `${city} 지금 ${t}°C, ${d}!`);
    } catch (e) {
      msg = '날씨를 가져오지 못했어요. 인터넷과 도시 이름을 확인해 주세요.';
    }
    if (ser === serial) show('a', msg, 5000, 0);
  }

  // ---------- e: 배터리 (노트북) ----------
  if (navigator.getBattery) {
    navigator.getBattery().then((b) => {
      let armed = true;
      const check = () => {
        if (b.charging) { armed = true; return; }
        const pct = Math.round(b.level * 100);
        if (pct <= cfg.batteryPct && armed) {
          armed = false;
          show('e', pick(`${U()}님 배터리 잔량이 ${cfg.batteryPct}% 정도만 남았습니다.`, `${U()}! 배터리가 ${cfg.batteryPct}%밖에 안남았어..`), 3000, cfg.fx.e);
        }
      };
      b.addEventListener('levelchange', check);
      b.addEventListener('chargingchange', check);
      check();
    }).catch(() => {});
  }

  // ---------- f: 같은 앱 오래 사용 ----------
  let fgName = null, nextWarn = 0;
  api.onFg(({ name, idle: idleSec }) => {
    if (!cfg.fOn) return;
    const now = Date.now();
    if (idleSec > 120) { fgName = null; return; }
    if (name !== fgName) { fgName = name; nextWarn = now + 30 * 60000; }
    if (IGN.has(name) || name.includes('도우미')) return;
    if (now >= nextWarn) {
      while (nextWarn <= now) nextWarn += 30 * 60000;
      show('a', pick(`${U()}님? 슬슬 휴식을 취하시는게 좋을것 같습니다.`, `${U()}! 너무 오래 하는거 아니야?`), 3000, cfg.fx.f);
    }
  });

  // ---------- g: 알림 (실험 기능) ----------
  api.onNotif((d) => {
    if ((!d.test && !cfg.notifOn) || gone) return;
    show('g', pick([`${U()}님 `, d.name, '에서 문자가 왔습니다.'], [`${U()}! `, d.name, '에서 문자가 왔어!']), 5000, cfg.fx.g);
    notifTarget = d.id || null;
  });

  // ---------- 설정 변경 / 재시작 ----------
  api.onConfig((c) => {
    const old = cfg;
    cfg = c;
    applySize(); place(px, py);
    curKey = null;
    if (!walking) setImage(stateImage());
    if (old.hOn !== c.hOn) resetIdle();
    sched();
    if (!c.walkOn) {
      stopWalk();
      if (parked) parked = false;
      if (flipped) { flipped = false; applyFlip(); }
    } else {
      if (!c.faceKeep && !walking && flipped) { flipped = false; applyFlip(); }
      schedWalk();
    }
  });
  api.onRestart(() => { gone = false; pet.style.display = ''; toA(); });
  addEventListener('resize', () => place(px, py));

  // ---------- 시작 ----------
  applySize();
  applyFlip();
  place(cfg.posX == null ? W() - S() - 24 : cfg.posX, cfg.posY == null ? Math.round(H() / 2) : cfg.posY);
  toA();
  resetIdle();
})();
