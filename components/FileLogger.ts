import { getRNFS } from './rnfs';

const LEGACY_LOG_FILE = '/sdcard/INBOX/localsend-plugin.log';
let legacyLogRemoved = false;

function ts(): string {
  const d = new Date();
  const pad = (n: number, len = 2) => String(n).padStart(len, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}.${pad(d.getMilliseconds(), 3)}`;
}

function removeLegacyLog() {
  legacyLogRemoved = true;
  const RNFS = getRNFS();
  if (!RNFS) return;
  for (const path of [LEGACY_LOG_FILE, `${LEGACY_LOG_FILE}.bak`]) {
    RNFS.exists(path).then(exists => (exists ? RNFS.unlink(path) : undefined)).catch(() => {});
  }
}

function appendLine(line: string) {
  if (!legacyLogRemoved) removeLegacyLog();
  console.log('[FileLogger]', line);
}

export const FileLogger = {
  logTextReceived(source: string, text: string) {
    const preview = text.length > 100 ? text.slice(0, 100) + '...' : text;
    appendLine(`[${ts()}][TextRecv] source=${source} len=${text.length} preview=${preview}`);
  },

  logTextInserted(page: number, rect: { left: number; top: number; right: number; bottom: number }, textLen: number) {
    appendLine(`[${ts()}][TextInsert] page=${page} rect=${JSON.stringify(rect)} textLen=${textLen}`);
  },

  logEvent(tag: string, message: string) {
    appendLine(`[${ts()}][${tag}] ${message}`);
  },
};
