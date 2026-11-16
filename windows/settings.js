(async () => {
  let cfg = await api.getConfig();
  const root = document.getElementById('app');
  const EFF = ['없음', '통통 튀기', '좌우 흔들기', '커졌다 작아지기', '회전 흔들기', '깜빡이기', '위에서 떨어지기'].map((n, i) => [i, n]);
  const getP = (o, k) => k.split('.').reduce((a, c) => (a == null ? a : a[c]), o);
  const setP = (k, v) => { const o = {}; let c = o; const ks = k.split('.'); ks.forEach((x, i) => { if (i === ks.length - 1) c[x] = v; else c = c[x] = {}; }); return o; };

  // [종류, 키, 라벨, ...] : sec(제목) / note / text / chk / sel / range
  const F = [
    { t: 'sec', l: '기본 정보' },
    { t: 'text', k: 'user', l: '사용자명 (말풍선에서 부르는 이름)' },
    { t: 'text', k: 'charName', l: '캐릭터명' },
    { t: 'sel', k: 'hon', l: '말투', o: [[true, '존칭 사용 (~입니다, ~님)'], [false, '존칭 사용 안 함 (~야, ~해)']] },
    { t: 'range', k: 'sizePx', l: '캐릭터 크기', u: 'px', min: 48, max: 200 },
    { t: 'chk', k: 'autostart', l: '윈도우를 켤 때 자동으로 실행' },

    { t: 'sec', l: '말풍선과 바 모양' },
    { t: 'range', k: 'bubbleSp', l: '말풍선 글자 크기', u: 'px', min: 10, max: 24 },
    { t: 'range', k: 'bubbleAlpha', l: '말풍선 투명도', u: '%', min: 30, max: 100 },
    { t: 'range', k: 'barPx', l: '바 두께 (아래·양쪽 공통)', u: 'px', min: 40, max: 200 },
    { t: 'note', l: '바가 클수록 놓으면 종료되는 영역이 넓어져 실수로 종료하기 쉬워요.' },

    { t: 'sec', l: '꾹 누르기 (d)' },
    { t: 'sel', k: 'longMs', l: '들어 올리는 데 필요한 시간', o: [[1000, '1초'], [500, '0.5초 (빠르게)']] },

    { t: 'sec', l: '더블 클릭 메뉴 (c)' },
    { t: 'sel', k: 'menuMode', l: '메뉴 종류', o: [[1, '날씨 + 앱 1개 + O/X 룰렛'], [2, '앱 바로가기 3개']] },
    { t: 'text', k: 'city', l: '날씨를 볼 도시 (인터넷 필요)' },
    { t: 'apps' },
    { t: 'note', l: '메뉴가 열려 있을 때 바깥을 클릭하면 메뉴가 닫혀요. 이때의 클릭은 아래 창에 전달되지 않아요.' },

    { t: 'sec', l: '배터리 경고 (e)' },
    { t: 'range', k: 'batteryPct', l: '배터리가 이 % 이하일 때', u: '%', min: 5, max: 50 },
    { t: 'note', l: '노트북에서만 동작해요. 한 번 알린 뒤에는 충전을 시작하기 전까지 다시 알리지 않아요.' },

    { t: 'sec', l: '혼잣말 (h)' },
    { t: 'chk', k: 'hOn', l: '혼잣말 사용' },
    { t: 'sel', k: 'hIdx', l: '혼잣말 간격', o: [[0, '10초'], [1, '20초'], [2, '1분'], [3, '3분']], if: (c) => c.hOn },
    { t: 'note', l: '혼잣말을 끄면 대기(i) 상태가 5분 대신 30초 뒤에 시작돼요.' },

    { t: 'sec', l: '같은 프로그램 오래 사용 (f)' },
    { t: 'chk', k: 'fOn', l: '같은 프로그램을 30분 넘게 쓰면 알려주기' },
    { t: 'note', l: '지금 앞에 있는 프로그램 이름만 이 PC 안에서 확인하고, 어디에도 저장하거나 보내지 않아요. 끄면 확인 자체를 하지 않아요.' },

    { t: 'sec', l: '알림 (g) — 실험 기능' },
    { t: 'chk', k: 'notifOn', l: '다른 프로그램의 알림이 오면 캐릭터가 알려주기' },
    { t: 'sel', k: 'notifOpen', l: '말풍선이 떠 있을 때 캐릭터를 클릭하면', o: [[0, '해당 알림 화면으로 이동 (가능한 경우)'], [1, '프로그램만 열기']], if: (c) => c.notifOn },
    { t: 'notif' },
    { t: 'note', l: '윈도우가 알림을 저장해 두는 파일에서 “어떤 프로그램의 알림이 왔는지”를 확인해요. 알림 제목과 내용은 사용하지 않고, 프로그램 이름과 클릭했을 때 열 웹 주소만 써요. 확인하는 동안 만드는 임시 복사본은 매번 바로 지워요. 윈도우 버전이나 설정에 따라 동작하지 않을 수 있고, 위 상태 줄에 이유가 표시돼요.' },
    { t: 'note', l: '“해당 알림 화면으로 이동”은 알림에 웹 주소가 들어 있는 경우에만 그 화면으로 가고, 그 외에는 프로그램을 열거나 앞으로 가져오는 데까지만 돼요. 프로그램마다 결과가 달라요.' },

    { t: 'sec', l: '캐릭터 움직임 효과' },
    { t: 'sel', k: 'fxRepeat', l: '움직임 방식', o: [[true, '말풍선이 끝날 때까지 계속 반복'], [false, '한 번만 실행']] },
    { t: 'sel', k: 'fxSpeed', l: '속도', o: [[0, '느리게'], [1, '보통'], [2, '빠르게']] },
    { t: 'sel', k: 'fx.b', l: 'b. 클릭했을 때', o: EFF },
    { t: 'sel', k: 'fx.c', l: 'c. 더블 클릭했을 때', o: EFF },
    { t: 'sel', k: 'fx.d', l: 'd. 꾹 눌러 들고 있을 때', o: EFF },
    { t: 'sel', k: 'fx.e', l: 'e. 배터리 부족', o: EFF },
    { t: 'sel', k: 'fx.f', l: 'f. 같은 프로그램 오래 사용', o: EFF },
    { t: 'sel', k: 'fx.g', l: 'g. 알림이 왔을 때', o: EFF },
    { t: 'note', l: '더블 클릭(c)의 효과는 항상 한 번만 실행돼요.' },

    { t: 'sec', l: '이동 (돌아다니기)' },
    { t: 'chk', k: 'walkOn', l: '이동 사용' },
    { t: 'sel', k: 'walkMode', l: '시작 대기 방식', o: [[0, '선택한 시간(고정)'], [1, '5~15초 랜덤']], if: (c) => c.walkOn },
    { t: 'range', k: 'walkWait', l: '시작 대기', u: '초', min: 5, max: 15, if: (c) => c.walkOn && c.walkMode === 0 },
    { t: 'sel', k: 'walkSpeed', l: '속도', o: [[0, '느리게'], [1, '보통'], [2, '빠르게']], if: (c) => c.walkOn },
    { t: 'sel', k: 'walkRange', l: '움직이는 범위', o: [[0, '화면 전체'], [1, '아래쪽'], [2, '가로로만']], if: (c) => c.walkOn },
    { t: 'sel', k: 'walkFace', l: '이미지가 원래 바라보는 방향 (모든 이미지 공통)', o: [[0, '오른쪽'], [1, '왼쪽']], if: (c) => c.walkOn },
    { t: 'chk', k: 'faceKeep', l: '멈춘 뒤에도 마지막 방향 유지 (모든 이미지에 적용)', if: (c) => c.walkOn },
    { t: 'note', l: '모든 이미지를 오른쪽을 바라보는 모습으로 만들면 가장 편해요. 마지막으로 오른쪽으로 걸었다면 그대로, 왼쪽으로 걸었다면 모든 이미지가 좌우 반전돼요. 이미지 안의 글자도 같이 뒤집혀 보여요.', if: (c) => c.walkOn },
    { t: 'note', l: '이동을 켜고 캐릭터를 꾹 눌러 들면 화면 양쪽에 세로 바가 생겨요. 바 위에서 놓으면 캐릭터가 거기서 쉬고(기본 이미지) 이동이 멈춰요. 다시 꾹 눌러 바 밖에 내려놓으면 이동이 재개돼요. 이동은 대기(i) 시간에 영향을 주지 않아요.', if: (c) => c.walkOn },

    { t: 'sec', l: '캐릭터 이미지' },
    { t: 'note', l: 'GIF, PNG, JPG, WebP를 쓸 수 있어요. 정사각형 이미지를 권장하며, 정사각형이 아니면 틀 안에 맞춰서 보여줘요. 설정하지 않으면 기본 이모지가 나와요.' },
    { t: 'imgs' },
  ];

  const SLOTS = [
    ['a', 'a. 기본 상태', '평소에 계속 반복되는 기본 모습이에요.'],
    ['b', 'b. 클릭했을 때', '캐릭터를 한 번 클릭했을 때 나오는 모습이에요.'],
    ['d', 'd. 꾹 눌러 들고 있을 때', '꾹 눌러서 들고 있는 동안 나오는 모습이에요.'],
    ['e', 'e. 배터리 부족', '배터리가 설정한 %까지 떨어졌을 때 나오는 모습이에요.'],
    ['i', 'i. 대기 상태', '5분(혼잣말을 끄면 30초) 동안 아무 일도 없을 때 계속 반복되는 모습이에요.'],
    ['g', 'g. 알림이 왔을 때', '다른 프로그램의 알림이 왔을 때 나오는 모습이에요. 설정하지 않으면 기본(a) 이미지를 그대로 써요.'],
    ['w', '이동. 돌아다닐 때', '오른쪽을 바라보는 모습으로 만들어 주세요. 왼쪽으로 갈 때 자동으로 좌우 반전돼요. 설정하지 않으면 기본(a) 이미지를 써요.'],
  ];
  const EMO = { a: '😺', b: '😸', d: '😲', e: '😿', i: '😴', w: '🐾', g: '😮' };

  const conds = []; // [element, fn]
  const dyn = { apps: null, imgs: null, notif: null };
  let notifStatus = await api.notifStatus();

  function mkRow(label) {
    const r = document.createElement('div'); r.className = 'row';
    const l = document.createElement('label'); l.textContent = label; r.appendChild(l);
    return r;
  }
  function build() {
    root.textContent = '';
    for (const f of F) {
      let el;
      if (f.t === 'sec') { el = document.createElement('h2'); el.textContent = f.l; }
      else if (f.t === 'note') { el = document.createElement('p'); el.className = 'note'; el.textContent = f.l; }
      else if (f.t === 'apps') { el = document.createElement('div'); dyn.apps = el; }
      else if (f.t === 'imgs') { el = document.createElement('div'); dyn.imgs = el; }
      else if (f.t === 'notif') { el = document.createElement('div'); dyn.notif = el; }
      else {
        el = mkRow(f.l);
        const cur = getP(cfg, f.k);
        if (f.t === 'text') {
          const i = document.createElement('input'); i.type = 'text'; i.value = cur ?? '';
          i.oninput = () => api.setConfig(setP(f.k, i.value.trim()));
          el.appendChild(i);
        } else if (f.t === 'chk') {
          const i = document.createElement('input'); i.type = 'checkbox'; i.checked = !!cur;
          i.onchange = () => api.setConfig(setP(f.k, i.checked));
          el.appendChild(i);
        } else if (f.t === 'sel') {
          const s = document.createElement('select');
          f.o.forEach(([v, n], idx) => { const op = document.createElement('option'); op.value = idx; op.textContent = n; s.appendChild(op); });
          const at = f.o.findIndex(([v]) => v === cur);
          s.value = at < 0 ? 0 : at;
          s.onchange = () => api.setConfig(setP(f.k, f.o[+s.value][0]));
          el.appendChild(s);
        } else if (f.t === 'range') {
          const i = document.createElement('input'); i.type = 'range'; i.min = f.min; i.max = f.max; i.value = cur;
          const v = document.createElement('span'); v.className = 'val'; v.textContent = cur + f.u;
          i.oninput = () => { v.textContent = i.value + f.u; api.setConfig(setP(f.k, +i.value)); };
          el.append(i, v);
        }
      }
      if (f.if) conds.push([el, f.if]);
      root.appendChild(el);
    }
    refresh();
  }

  function renderApps() {
    const box = dyn.apps; box.textContent = '';
    for (let i = 0; i < 3; i++) {
      const r = mkRow('바로가기 프로그램 ' + (i + 1));
      const a = cfg.apps[i];
      const t = document.createElement('span'); t.style.flex = '1'; t.textContent = a ? a.name : '선택 안 됨';
      const b1 = document.createElement('button'); b1.textContent = '선택'; b1.onclick = async () => { cfg = await api.pickApp(i); refresh(); };
      const b2 = document.createElement('button'); b2.textContent = '해제'; b2.onclick = async () => { cfg = await api.clearApp(i); refresh(); };
      r.append(t, b1, b2);
      box.appendChild(r);
    }
    const n = document.createElement('p'); n.className = 'note';
    n.textContent = '첫 번째 메뉴 종류는 프로그램 1만, 두 번째 종류는 1~3을 모두 사용해요. exe나 바로가기(.lnk) 파일을 고를 수 있어요.';
    box.appendChild(n);
  }

  function renderNotif() {
    const box = dyn.notif; box.textContent = '';
    const st = document.createElement('p'); st.className = 'note'; st.id = 'nst'; st.textContent = '상태: ' + notifStatus;
    const tb = document.createElement('button'); tb.textContent = '알림 말풍선 테스트'; tb.onclick = () => api.testNotif();
    box.append(st, tb);
    if (cfg.notifSeen.length) {
      const t = document.createElement('p'); t.className = 'note'; t.textContent = '알림을 받을 프로그램 (최근에 알림이 온 프로그램, 체크를 풀면 알리지 않아요)';
      box.appendChild(t);
      for (const a of cfg.notifSeen) {
        const lab = document.createElement('label'); lab.style.display = 'block';
        const cb = document.createElement('input'); cb.type = 'checkbox'; cb.checked = !cfg.notifMuted.includes(a.id);
        cb.onchange = () => { const mset = new Set(cfg.notifMuted); if (cb.checked) mset.delete(a.id); else mset.add(a.id); api.setConfig({ notifMuted: [...mset] }); };
        lab.append(cb, ' ' + a.name);
        box.appendChild(lab);
      }
    }
  }

  function renderImgs() {
    const box = dyn.imgs; box.textContent = '';
    for (const [k, title, desc] of SLOTS) {
      const im = cfg.images[k];
      const d = document.createElement('div'); d.className = 'slot';
      const pv = document.createElement('div'); pv.className = 'pv';
      if (im) { const g = new Image(); g.src = api.fileUrl(im.file); pv.appendChild(g); } else pv.textContent = EMO[k];
      const tx = document.createElement('div'); tx.className = 'tx';
      const b = document.createElement('b'); b.textContent = title;
      tx.append(b, desc);
      const bt = document.createElement('div'); bt.className = 'bt';
      const b1 = document.createElement('button'); b1.textContent = '이미지 선택'; b1.onclick = async () => { cfg = await api.pickImage(k); refresh(); };
      const b2 = document.createElement('button'); b2.textContent = '기본으로'; b2.onclick = async () => { cfg = await api.resetImage(k); refresh(); };
      bt.append(b1, b2);
      d.append(pv, tx, bt);
      box.appendChild(d);
    }
  }

  function refresh() {
    for (const [el, fn] of conds) el.hidden = !fn(cfg);
    if (dyn.apps) renderApps();
    if (dyn.imgs) renderImgs();
    if (dyn.notif) renderNotif();
  }

  api.onConfig((c) => { cfg = c; refresh(); });
  api.onNotifStatus((x) => { notifStatus = x; const e = document.getElementById('nst'); if (e) e.textContent = '상태: ' + x; });
  build();
})();
