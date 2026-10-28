'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { spawn } = require('node:child_process');

const MAX_BYTES = 64 * 1024;
const SCRIPT = path.join(__dirname, 'key-vault.ps1');
const FORMAT = 'domtrack-key-vault';
class KeyVaultError extends Error {
  constructor(code = 'VAULT_FAILED') {
    const messages = {
      VAULT_WINDOWS_ONLY: 'Защищённое хранение API-ключа доступно только в Windows.',
      VAULT_KEY_INVALID: 'API-ключ должен содержать от 1 до 500 символов без перевода строки.',
      VAULT_CONFIG: 'Не удалось настроить защищённое хранилище ключа.',
      VAULT_READ: 'Не удалось прочитать защищённый API-ключ. Исходный файл сохранён.',
      VAULT_DECRYPT: 'Не удалось расшифровать API-ключ для текущего пользователя Windows. Исходный файл сохранён.',
      VAULT_SAVE: 'Не удалось зашифровать и сохранить API-ключ. Предыдущее значение не изменено.',
      VAULT_REMOVE: 'Не удалось удалить сохранённый API-ключ.',
      VAULT_HELPER: 'Windows DPAPI недоступен. API-ключ не сохранён.'
    };
    super(messages[code] || 'Операция защищённого хранилища не выполнена.');
    this.name = 'KeyVaultError'; this.code = code;
    this.statusCode = code === 'VAULT_KEY_INVALID' ? 400 : 500;
  }
}
function validateKey(value) { return typeof value === 'string' && value.length >= 1 && value.length <= 500 && !/[\r\n\u0000]/.test(value); }
function validCipher(value) {
  return typeof value === 'string' && value.length > 0 && value.length <= MAX_BYTES - 1024 && /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(value) && Buffer.from(value, 'base64').toString('base64') === value;
}

/** Test hook: runner({operation, value}) -> Promise<string>; no shell interpolation. */
function createPowerShellRunner({ spawnImpl = spawn } = {}) {
  return async function run(input) {
    if (!input || !['protect', 'unprotect'].includes(input.operation) || typeof input.value !== 'string') throw new KeyVaultError('VAULT_HELPER');
    const payload = JSON.stringify({ operation: input.operation, value: input.value });
    if (Buffer.byteLength(payload, 'utf8') > MAX_BYTES) throw new KeyVaultError('VAULT_HELPER');
    const environment = Object.fromEntries(Object.entries(process.env).filter(([name]) => !/(?:api.?key|token|secret|password)/i.test(name)));
    const executable = path.join(process.env.SystemRoot || process.env.WINDIR || 'C:\\Windows', 'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe');
    async function invoke() {
      const args = ['-NoProfile', '-NonInteractive', '-WindowStyle', 'Hidden', '-File', SCRIPT];
      return new Promise((resolve, reject) => {
        let child; let ended = false; let output = ''; let outputBytes = 0; let errorBytes = 0;
        const finish = (error, result) => { if (ended) return; ended = true; clearTimeout(timer); if (error) reject(error); else resolve(result); };
        const timer = setTimeout(() => { child?.kill?.(); finish(new KeyVaultError('VAULT_HELPER')); }, 15000);
        try { child = spawnImpl(executable, args, { windowsHide: true, shell: false, stdio: ['pipe', 'pipe', 'pipe'], env: environment }); }
        catch { finish(new KeyVaultError('VAULT_HELPER')); return; }
        child.on('error', () => finish(new KeyVaultError('VAULT_HELPER')));
        child.stdout?.on('data', chunk => {
          outputBytes += Buffer.byteLength(chunk);
          if (outputBytes > MAX_BYTES) { child.kill?.(); finish(new KeyVaultError('VAULT_HELPER')); return; }
          output += Buffer.from(chunk).toString('utf8');
        });
        child.stderr?.on('data', chunk => {
          errorBytes += Buffer.byteLength(chunk);
          if (errorBytes > 8192) { child.kill?.(); finish(new KeyVaultError('VAULT_HELPER')); return; }
        });
        child.on('close', code => {
          if (ended) return;
          // A denied script stays denied; never retry its contents through another host mode.
          if (code !== 0) { finish(new KeyVaultError('VAULT_HELPER')); return; }
          try {
            const result = JSON.parse(output.replace(/^\uFEFF/, '').trim());
            if (!result || result.ok !== true || typeof result.value !== 'string') throw new Error();
            finish(null, result.value);
          } catch { finish(new KeyVaultError('VAULT_HELPER')); }
        });
        child.stdin?.on('error', () => { /* A policy-rejected child may close stdin before reading. */ });
        try { child.stdin.end(payload, 'utf8'); }
        catch { child.kill?.(); finish(new KeyVaultError('VAULT_HELPER')); }
      });
    }
    return invoke();
  };
}

function createKeyVault({ file, platform = process.platform, runner = createPowerShellRunner() } = {}) {
  if (typeof file !== 'string' || !file || typeof runner !== 'function') throw new KeyVaultError('VAULT_CONFIG');
  const filename = path.resolve(file);
  let queue = Promise.resolve();
  function assertWindows() { if (platform !== 'win32') throw new KeyVaultError('VAULT_WINDOWS_ONLY'); }
  function serial(operation) { const result = queue.then(operation); queue = result.catch(() => {}); return result; }
  async function load() {
    return serial(async () => {
      assertWindows();
      let handle;
      let body;
      try {
        handle = await fs.promises.open(filename, 'r');
        const stat = await handle.stat();
        if (!stat.isFile() || stat.size > MAX_BYTES) throw new Error();
        const buffer = Buffer.alloc(MAX_BYTES + 1);
        let offset = 0;
        while (offset < buffer.length) { const part = await handle.read(buffer, offset, buffer.length - offset, offset); if (!part.bytesRead) break; offset += part.bytesRead; }
        if (offset > MAX_BYTES) throw new Error();
        body = buffer.subarray(0, offset).toString('utf8');
      } catch (error) { if (error.code === 'ENOENT') return null; throw new KeyVaultError('VAULT_READ'); }
      finally { if (handle) await handle.close().catch(() => {}); }
      let cipher;
      try {
        const value = JSON.parse(body);
        if (!value || value.format !== FORMAT || value.version !== 1 || value.protection !== 'windows-dpapi-current-user' || !validCipher(value.ciphertext) || Object.keys(value).some(name => !['format', 'version', 'protection', 'ciphertext'].includes(name))) throw new Error();
        cipher = value.ciphertext;
      } catch { throw new KeyVaultError('VAULT_READ'); }
      try {
        const key = await runner({ operation: 'unprotect', value: cipher });
        if (!validateKey(key)) throw new Error();
        return key;
      } catch { throw new KeyVaultError('VAULT_DECRYPT'); }
    });
  }
  async function save(key) {
    return serial(async () => {
      assertWindows();
      if (!validateKey(key)) throw new KeyVaultError('VAULT_KEY_INVALID');
      let cipher;
      try { cipher = await runner({ operation: 'protect', value: key }); if (!validCipher(cipher)) throw new Error(); }
      catch { throw new KeyVaultError('VAULT_SAVE'); }
      const serialized = JSON.stringify({ format: FORMAT, version: 1, protection: 'windows-dpapi-current-user', ciphertext: cipher }, null, 2);
      if (Buffer.byteLength(serialized, 'utf8') > MAX_BYTES) throw new KeyVaultError('VAULT_SAVE');
      const temporary = `${filename}.tmp-${process.pid}-${crypto.randomBytes(8).toString('hex')}`;
      let handle;
      try {
        await fs.promises.mkdir(path.dirname(filename), { recursive: true, mode: 0o700 });
        handle = await fs.promises.open(temporary, 'wx', 0o600);
        await handle.writeFile(serialized, 'utf8'); await handle.sync(); await handle.close(); handle = null;
        await fs.promises.rename(temporary, filename);
      } catch { throw new KeyVaultError('VAULT_SAVE'); }
      finally { if (handle) await handle.close().catch(() => {}); await fs.promises.unlink(temporary).catch(() => {}); }
    });
  }
  async function remove() {
    return serial(async () => { assertWindows(); try { await fs.promises.unlink(filename); } catch (error) { if (error.code !== 'ENOENT') throw new KeyVaultError('VAULT_REMOVE'); } });
  }
  return { load, save, remove };
}

module.exports = { createKeyVault, createPowerShellRunner, KeyVaultError };
