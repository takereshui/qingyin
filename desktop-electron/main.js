const { app, BrowserWindow, shell, session } = require('electron');
const path = require('node:path');

const isDev = !app.isPackaged;

function createWindow() {
  const window = new BrowserWindow({
    width: 1280,
    height: 820,
    minWidth: 960,
    minHeight: 640,
    title: '轻音',
    backgroundColor: '#170d17',
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });

  // webapp is copied into the packaged app, so this path also works inside app.asar.
  const indexPath = path.join(__dirname, 'webapp', 'index.html');
  window.loadFile(indexPath).catch((error) => {
    window.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(
      `<h2 style="font-family:sans-serif;color:#fff;background:#170d17;padding:32px">轻音启动失败</h2><pre style="white-space:pre-wrap;color:#fbb">${String(error)}</pre>`,
    )}`);
  });

  window.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:\/\//i.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });

  if (isDev) window.webContents.openDevTools({ mode: 'detach' });
}

app.whenReady().then(() => {
  session.defaultSession.webRequest.onHeadersReceived((details, callback) => {
    const responseHeaders = { ...details.responseHeaders };
    delete responseHeaders['content-security-policy'];
    delete responseHeaders['Content-Security-Policy'];
    callback({ responseHeaders });
  });

  createWindow();
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});
