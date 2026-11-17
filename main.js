const { app, BrowserWindow, Tray, Menu, ipcMain, dialog, shell, screen, nativeImage, powerMonitor } = require('electron');
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');
const { animDuration } = require('./anim');

const ICON_B64 = 'iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAAAkElEQVR42u2XQQqAMAwE+5Z8yIMnv+ev/EfvioIHQRqzpl0CCeSWzA5U1JaSpVRdl52x+4AgIHTvFWKFoXsq6AsM2THBWkDrPCxgbWo4LJECVAHvcLNEClAFWgARUUO0GVjgBN/9ZyauAP0Ihj2IdAGLxDZPV9PeA3SBUB+keL9kXhJutyNaOCrR/b44PLRXHR9Y04BOZZ3QAAAAAElFTkSuQmCC';

if (!app.requestSingleInstanceLock()) {
  app.quit();
}

const DEFAULTS = {
  user: '사용자', charName: '도우미', hon: true, sizePx: 96, autostart: false,
  bubbleSp: 14, bubbleAlpha: 95, barPx: 90, longMs: 1000,
  menuMode: 1, city: '서울', apps: [null, null, null], batteryPct: 20,
  hOn: true, hIdx: 2, fOn: true,
  notifOn: false, notifOpen: 0, notifMuted: [], notifSeen: [],
  fxRepeat: true, fxSpeed: 1, fx: { b: 1, c: 3, d: 2, e: 2, f: 1, g: 5 },
  walkOn: false, walkMode: 0, walkWait: 10, walkSpeed: 1, walkRange: 0, walkFace: 0, faceKeep: true,
  images: { a: null, b: null, d: null, e: null, i: null, w: null, g: null },
  parked: false, flipped: false, posX: null, posY: null,
};
const RUNTIME = ['posX', 'posY', 'parked', 'flipped'];

let cfg = {};
let overlay = null;
let settingsWin = null;
let tray = null;
let fgProc = null;
let saveTimer = null;
let firstRun = false;

const cfgFile = () => path.join(app.getPath('userData'), 'config.json');
const logFile = () => path.join(app.getPath('userData'), 'log.txt');
function log(msg) {
  try {
    const f = logFile();
    fs.mkdirSync(path.dirname(f), { recursive: true });
    let old = '';
    try { old = fs.readFileSync(f, 'utf8'); } catch (e) {}
    fs.writeFileSync(f, (old + new Date().toISOString() + ' ' + msg + '\n').slice(-60000));
  } catch (e) {}
}
function attachLog(win, name) {
  win.webContents.on('console-message', (_e, level, message) => {
    if (typeof level === 'number' && level >= 2) log(name + ' 콘솔: ' + message);
  });
  win.webContents.on('render-process-gone', (_e, d) => log(name + ' 화면이 종료됨: ' + (d && d.reason)));
  win.webContents.on('did-fail-load', (_e, code, desc, url) => log(name + ' 불러오기 실패: ' + desc + ' ' + url));
}
process.on('uncaughtException', (e) => log('main 오류: ' + (e && e.stack ? e.stack : e)));
const isObj = (v) => v && typeof v === 'object' && !Array.isArray(v);
function merge(t, p) {
  for (const k of Object.keys(p)) {
    if (isObj(p[k]) && isObj(t[k])) merge(t[k], p[k]);
    else t[k] = p[k];
  }
  return t;
}
function loadCfg() {
  cfg = JSON.parse(JSON.stringify(DEFAULTS));
  try {
    merge(cfg, JSON.parse(fs.readFileSync(cfgFile(), 'utf8')));
  } catch (e) {
    firstRun = true;
  }
}
function saveCfg() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    try {
      fs.mkdirSync(path.dirname(cfgFile()), { recursive: true });
      fs.writeFileSync(cfgFile(), JSON.stringify(cfg, null, 2));
    } catch (e) {}
  }, 300);
}
function broadcast() {
  for (const w of BrowserWindow.getAllWindows()) {
    if (!w.isDestroyed()) w.webContents.send('cfg:changed', cfg);
  }
}

// ---------- 창 ----------
function fitOverlay() {
  if (!overlay || overlay.isDestroyed()) return;
  overlay.setBounds(screen.getPrimaryDisplay().workArea);
}

function createOverlay() {
  const wa = screen.getPrimaryDisplay().workArea;
  overlay = new BrowserWindow({
    x: wa.x, y: wa.y, width: wa.width, height: wa.height,
    transparent: true, frame: false, resizable: false, movable: false,
    focusable: false, skipTaskbar: true, alwaysOnTop: true, hasShadow: false, show: false,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true, nodeIntegration: false, backgroundThrottling: false,
    },
  });
  attachLog(overlay, '캐릭터');
  overlay.setAlwaysOnTop(true, 'screen-saver');
  overlay.setIgnoreMouseEvents(true, { forward: true });
  overlay.loadFile(path.join(__dirname, 'overlay.html'));
  overlay.once('ready-to-show', () => overlay.showInactive());
  screen.on('display-metrics-changed', fitOverlay);
  screen.on('display-added', fitOverlay);
  screen.on('display-removed', fitOverlay);
}

function openSettings() {
  if (settingsWin && !settingsWin.isDestroyed()) {
    if (settingsWin.isMinimized()) settingsWin.restore();
    settingsWin.focus();
    return;
  }
  settingsWin = new BrowserWindow({
    width: 620, height: 840, title: '나의 도우미 설정', autoHideMenuBar: true,
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false },
  });
  settingsWin.setMenuBarVisibility(false);
  attachLog(settingsWin, '설정');
  settingsWin.loadFile(path.join(__dirname, 'settings.html'));
  settingsWin.on('closed', () => { settingsWin = null; });
}

function restartPet() {
  if (!overlay || overlay.isDestroyed()) return;
  overlay.showInactive();
  overlay.setAlwaysOnTop(true, 'screen-saver');
  overlay.webContents.send('pet:restart');
}

function createTray() {
  tray = new Tray(nativeImage.createFromDataURL('data:image/png;base64,' + ICON_B64));
  tray.setToolTip('나의 도우미');
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: '설정 열기', click: openSettings },
    { label: '캐릭터 다시 띄우기', click: restartPet },
    { type: 'separator' },
    { label: '종료', click: () => app.quit() },
  ]));
  tray.on('click', openSettings);
}

function applyAutostart() {
  try {
    app.setLoginItemSettings({
      openAtLogin: !!cfg.autostart,
      path: process.env.PORTABLE_EXECUTABLE_FILE || process.execPath,
    });
  } catch (e) {}
}

// ---------- 현재 사용 중인 프로그램 감시 (같은 앱 오래 사용 기능) ----------
function startFg() {
  if (fgProc || process.platform !== 'win32') return;
  const script = [
    '[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)',
    'Add-Type @"',
    'using System;',
    'using System.Runtime.InteropServices;',
    'public class FG {',
    '  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();',
    '  [DllImport("user32.dll")] public static extern int GetWindowThreadProcessId(IntPtr h, out int pid);',
    '}',
    '"@',
    'while ($true) {',
    '  $h = [FG]::GetForegroundWindow()',
    '  $p = 0',
    '  [void][FG]::GetWindowThreadProcessId($h, [ref]$p)',
    '  try { (Get-Process -Id $p -ErrorAction Stop).ProcessName } catch { "" }',
    '  Start-Sleep -Seconds 5',
    '}',
  ].join('\n');
  try {
    fgProc = spawn('powershell.exe', [
      '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
      '-EncodedCommand', Buffer.from(script, 'utf16le').toString('base64'),
    ], { windowsHide: true });
    fgProc.stdout.on('data', (d) => {
      for (const line of d.toString().split(/\r?\n/)) {
        const name = line.trim().toLowerCase();
        if (!name || !overlay || overlay.isDestroyed()) continue;
        overlay.webContents.send('fg:app', { name, idle: powerMonitor.getSystemIdleTime() });
      }
    });
    fgProc.on('exit', () => { fgProc = null; });
    fgProc.on('error', () => { fgProc = null; });
  } catch (e) {
    fgProc = null;
  }
}
function stopFg() {
  if (fgProc) {
    try { fgProc.kill(); } catch (e) {}
    fgProc = null;
  }
}

// ---------- 알림 감시 (실험 기능) ----------
let notifProc = null;
let notifStatus = '꺼져 있어요';
let notifBuf = '';
const lastByApp = {};

function setNotifStatus(s) {
  notifStatus = s;
  for (const w of BrowserWindow.getAllWindows()) {
    if (!w.isDestroyed()) w.webContents.send('notif:status', s);
  }
}
function handleNotif(o) {
  if (!o || !o.app || !cfg.notifOn) return;
  lastByApp[o.app] = { launch: o.launch || '', at: o.at || '' };
  if (cfg.notifMuted.includes(o.app)) return;
  if (!cfg.notifSeen.some((x) => x.id === o.app)) {
    merge(cfg, { notifSeen: cfg.notifSeen.concat([{ id: o.app, name: o.name || o.app }]).slice(-40) });
    saveCfg(); broadcast();
  }
  if (overlay && !overlay.isDestroyed()) overlay.webContents.send('notif:new', { id: o.app, name: o.name || o.app });
}
function handleNotifLine(line) {
  line = line.replace(/^\uFEFF/, '').trim();
  if (!line) return;
  if (line === 'OK:ready') { setNotifStatus('감시 중이에요'); return; }
  if (line.startsWith('ERR:')) {
    const code = line.slice(4);
    setNotifStatus(code === 'no-db'
      ? '알림 저장 파일을 찾지 못했어요. 이 윈도우에서는 지원되지 않을 수 있어요'
      : '읽지 못했어요 (' + code + ')');
    return;
  }
  try { handleNotif(JSON.parse(line)); } catch (e) {}
}
function startNotif() {
  if (notifProc || process.platform !== 'win32') return;
  try {
    const script = path.join(app.getPath('temp'), 'myhelper_notif.ps1');
    fs.writeFileSync(script, '\uFEFF' + fs.readFileSync(path.join(__dirname, 'notif.ps1'), 'utf8'));
    setNotifStatus('시작하는 중...');
    notifBuf = '';
    notifProc = spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', script], { windowsHide: true });
    notifProc.stdout.setEncoding('utf8');
    notifProc.stdout.on('data', (d) => {
      notifBuf += d;
      const parts = notifBuf.split(/\r?\n/);
      notifBuf = parts.pop();
      parts.forEach(handleNotifLine);
    });
    notifProc.stderr.on('data', () => {});
    notifProc.on('exit', () => { notifProc = null; if (cfg.notifOn) setNotifStatus('감시가 멈췄어요'); });
    notifProc.on('error', () => { notifProc = null; setNotifStatus('PowerShell을 실행하지 못했어요'); });
  } catch (e) {
    notifProc = null;
    setNotifStatus('시작하지 못했어요 (' + e.message + ')');
  }
}
function stopNotif() {
  if (notifProc) { try { notifProc.kill(); } catch (e) {} notifProc = null; }
  setNotifStatus('꺼져 있어요');
}
async function openNotif(id) {
  const info = lastByApp[id] || {};
  // 알림에 웹 주소(https)가 들어 있고 설정이 허용하면 그 주소를 먼저 연다
  if (cfg.notifOpen === 0 && /^https?:\/\//i.test(info.launch || '') && String(info.at).toLowerCase() === 'protocol') {
    try { await shell.openExternal(info.launch); return; } catch (e) {}
  }
  if (!id) return;
  try { spawn('explorer.exe', ['shell:AppsFolder\\' + id], { detached: true, stdio: 'ignore' }).unref(); } catch (e) {}
}

// ---------- IPC ----------
ipcMain.on('log', (_e, msg) => log(String(msg)));
ipcMain.on('log:open', () => {
  try { if (!fs.existsSync(logFile())) log('기록 시작'); shell.showItemInFolder(logFile()); } catch (e) {}
});
ipcMain.handle('notif:status', () => notifStatus);
ipcMain.handle('notif:open', (_e, id) => openNotif(id));
ipcMain.on('notif:test', () => {
  if (overlay && !overlay.isDestroyed()) overlay.webContents.send('notif:new', { id: '', name: '테스트 앱', test: true });
});
ipcMain.handle('cfg:get', () => cfg);
ipcMain.handle('cfg:set', (_e, patch) => {
  merge(cfg, patch);
  saveCfg();
  if ('autostart' in patch) applyAutostart();
  if ('fOn' in patch) { if (cfg.fOn) startFg(); else stopFg(); }
  if ('notifOn' in patch) { if (cfg.notifOn) startNotif(); else stopNotif(); }
  if (!Object.keys(patch).every((k) => RUNTIME.includes(k))) broadcast();
  return cfg;
});
ipcMain.on('overlay:mouse', (_e, capture) => {
  if (overlay && !overlay.isDestroyed()) overlay.setIgnoreMouseEvents(!capture, { forward: true });
});
ipcMain.on('settings:open', openSettings);
ipcMain.handle('app:launch', (_e, p) => shell.openPath(p));
ipcMain.handle('app:icon', async (_e, p) => {
  try { return (await app.getFileIcon(p, { size: 'normal' })).toDataURL(); } catch (e) { return null; }
});
ipcMain.handle('app:pick', async (_e, i) => {
  const r = await dialog.showOpenDialog(settingsWin || undefined, {
    title: '바로가기로 열 프로그램 선택',
    filters: [{ name: '프로그램/바로가기', extensions: ['exe', 'lnk', 'bat', 'cmd'] }, { name: '모든 파일', extensions: ['*'] }],
    properties: ['openFile'],
  });
  if (r.canceled || !r.filePaths[0]) return cfg;
  const p = r.filePaths[0];
  const apps = cfg.apps.slice();
  apps[i] = { path: p, name: path.basename(p, path.extname(p)) };
  merge(cfg, { apps });
  saveCfg(); broadcast();
  return cfg;
});
ipcMain.handle('app:clear', (_e, i) => {
  const apps = cfg.apps.slice();
  apps[i] = null;
  merge(cfg, { apps });
  saveCfg(); broadcast();
  return cfg;
});
ipcMain.handle('img:pick', async (_e, key) => {
  const r = await dialog.showOpenDialog(settingsWin || undefined, {
    title: '이미지 선택',
    filters: [{ name: '이미지', extensions: ['gif', 'png', 'jpg', 'jpeg', 'webp'] }],
    properties: ['openFile'],
  });
  if (r.canceled || !r.filePaths[0]) return cfg;
  try {
    const src = r.filePaths[0];
    const dir = path.join(app.getPath('userData'), 'images');
    fs.mkdirSync(dir, { recursive: true });
    const old = cfg.images[key];
    if (old && old.file) { try { fs.unlinkSync(old.file); } catch (e) {} }
    const dst = path.join(dir, key + '_' + Date.now() + path.extname(src).toLowerCase());
    fs.copyFileSync(src, dst);
    cfg.images[key] = { file: dst, loopMs: animDuration(fs.readFileSync(dst)), v: Date.now() };
    saveCfg(); broadcast();
  } catch (e) {
    dialog.showErrorBox('이미지를 불러오지 못했어요', String(e));
  }
  return cfg;
});
ipcMain.handle('img:reset', (_e, key) => {
  const old = cfg.images[key];
  if (old && old.file) { try { fs.unlinkSync(old.file); } catch (e) {} }
  cfg.images[key] = null;
  saveCfg(); broadcast();
  return cfg;
});
ipcMain.on('pet:exit', async () => {
  if (overlay && !overlay.isDestroyed()) overlay.hide();
  const r = await dialog.showMessageBox({
    type: 'question', buttons: ['다시 실행', '트레이에 두기'], defaultId: 0, cancelId: 1,
    title: '캐릭터를 종료했어요', message: '캐릭터를 다시 띄울까요?',
    detail: '나중에 다시 띄우려면 작업 표시줄 오른쪽 아래의 트레이 아이콘(숨겨진 아이콘 포함)에서 “캐릭터 다시 띄우기”를 누르세요.',
  });
  if (r.response === 0) restartPet();
});

// ---------- 시작 ----------
app.on('second-instance', openSettings);
app.on('window-all-closed', () => {});
app.on('before-quit', () => { stopFg(); stopNotif(); });

app.whenReady().then(() => {
  loadCfg();
  log('시작 (버전 ' + app.getVersion() + ')');
  createOverlay();
  createTray();
  applyAutostart();
  if (cfg.fOn) startFg();
  if (cfg.notifOn) startNotif();
  if (firstRun) openSettings();
});
