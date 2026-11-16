const { contextBridge, ipcRenderer } = require('electron');
const { pathToFileURL } = require('url');

contextBridge.exposeInMainWorld('api', {
  getConfig: () => ipcRenderer.invoke('cfg:get'),
  setConfig: (patch) => ipcRenderer.invoke('cfg:set', patch),
  onConfig: (cb) => ipcRenderer.on('cfg:changed', (_e, c) => cb(c)),
  setMouse: (capture) => ipcRenderer.send('overlay:mouse', capture),
  launch: (p) => ipcRenderer.invoke('app:launch', p),
  appIcon: (p) => ipcRenderer.invoke('app:icon', p),
  pickApp: (i) => ipcRenderer.invoke('app:pick', i),
  clearApp: (i) => ipcRenderer.invoke('app:clear', i),
  pickImage: (key) => ipcRenderer.invoke('img:pick', key),
  resetImage: (key) => ipcRenderer.invoke('img:reset', key),
  exitPet: () => ipcRenderer.send('pet:exit'),
  onRestart: (cb) => ipcRenderer.on('pet:restart', () => cb()),
  onFg: (cb) => ipcRenderer.on('fg:app', (_e, d) => cb(d)),
  openSettings: () => ipcRenderer.send('settings:open'),
  openNotif: (id) => ipcRenderer.invoke('notif:open', id),
  onNotif: (cb) => ipcRenderer.on('notif:new', (_e, d) => cb(d)),
  testNotif: () => ipcRenderer.send('notif:test'),
  notifStatus: () => ipcRenderer.invoke('notif:status'),
  onNotifStatus: (cb) => ipcRenderer.on('notif:status', (_e, s) => cb(s)),
  fileUrl: (p) => pathToFileURL(p).href,
});
