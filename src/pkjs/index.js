/* global Pebble, localStorage, navigator */

var configHtml = require('!!./loaders/html-as-string.js!./config.html');
var APP_VERSION = require('../../package.json').version;

var STORAGE_KEY = 'ez_sos_settings';
var PHONE_OS_KEY = 'ez_sos_phone_os';
var COMPANION_AT_KEY = 'ez_sos_companion_at';
var DEFAULT_PREFIX = 'EZ SOS: I need help.';
var REQUIRED_PREFIX = 'EZ SOS: ';
var DEFAULT_BODY = 'I need help.';
var DEFAULT_MODE = 'confirm';
var DEFAULT_HOLD_MS = 1500;
var HOLD_MS_PRESETS = [1000, 1500, 2000, 3000];
var CHUNK_SIZE = 800;
/** Companion presence is considered fresh for this long (ms). */
var COMPANION_FRESH_MS = 24 * 60 * 60 * 1000;

var GITHUB_REPO = 'https://github.com/caellach/pebble-ez-sos';
var RELEASES_LATEST = GITHUB_REPO + '/releases/latest';

function releaseTagUrl(version) {
  var v = String(version || '').replace(/^v/, '');
  if (!v) {
    return RELEASES_LATEST;
  }
  return GITHUB_REPO + '/releases/tag/v' + v;
}

function companionApkUrl(version) {
  var v = String(version || '').replace(/^v/, '');
  if (!v) {
    return RELEASES_LATEST;
  }
  return GITHUB_REPO + '/releases/download/v' + v + '/EZ_SOS-companion-' + v + '.apk';
}

function companionSettingsIntentUrl() {
  return (
    'intent://settings#Intent;scheme=ezsos;package=com.ezsos.companion;' +
    'category=android.intent.category.BROWSABLE;end'
  );
}

function normalizeMessagePrefix(raw) {
  var trimmed = typeof raw === 'string' ? raw.trim() : '';
  var body;
  if (trimmed.toLowerCase().indexOf('ez sos:') === 0) {
    body = trimmed.substring(trimmed.indexOf(':') + 1).replace(/^\s+/, '');
  } else {
    body = trimmed;
  }
  if (!body) {
    body = DEFAULT_BODY;
  }
  return REQUIRED_PREFIX + body;
}

function normalizeHoldMs(raw) {
  var value = typeof raw === 'number' ? raw : parseInt(raw, 10);
  if (!value || isNaN(value)) {
    return DEFAULT_HOLD_MS;
  }
  if (HOLD_MS_PRESETS.indexOf(value) !== -1) {
    return value;
  }
  var best = DEFAULT_HOLD_MS;
  var bestDist = Math.abs(value - best);
  for (var i = 0; i < HOLD_MS_PRESETS.length; i++) {
    var dist = Math.abs(value - HOLD_MS_PRESETS[i]);
    if (dist < bestDist) {
      best = HOLD_MS_PRESETS[i];
      bestDist = dist;
    }
  }
  return best;
}

function defaultSettings() {
  return {
    triggerMode: DEFAULT_MODE,
    holdMs: DEFAULT_HOLD_MS,
    messagePrefix: DEFAULT_PREFIX,
    contacts: [],
    watchAlarmSound: true,
    selfLocateAlarm: true,
    contacts: []
  };
}

function normalizeSettings(raw) {
  var base = defaultSettings();
  if (!raw || typeof raw !== 'object') {
    return base;
  }
  var mode = raw.triggerMode;
  if (mode !== 'single' && mode !== 'confirm' && mode !== 'hold') {
    mode = DEFAULT_MODE;
  }
  var contacts = Array.isArray(raw.contacts) ? raw.contacts : [];
  return {
    triggerMode: mode,
    holdMs: normalizeHoldMs(raw.holdMs),
    messagePrefix: normalizeMessagePrefix(raw.messagePrefix),
    contacts: contacts.map(function (c, i) {
      return {
        id: c && c.id != null ? String(c.id) : ('local-' + i),
        name: c && c.name != null ? String(c.name) : '',
        phone: c && c.phone != null ? String(c.phone) : '',
        enabled: !c || c.enabled !== false
      };
    }),
    watchAlarmSound: raw.watchAlarmSound !== false,
    selfLocateAlarm: raw.selfLocateAlarm !== false
  };
}

function loadSettings() {
  try {
    var raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) {
      return defaultSettings();
    }
    return normalizeSettings(JSON.parse(raw));
  } catch (e) {
    console.log('EZ SOS: failed to load settings: ' + e);
    return defaultSettings();
  }
}

function saveSettings(settings) {
  var normalized = normalizeSettings(settings);
  localStorage.setItem(STORAGE_KEY, JSON.stringify(normalized));
  return normalized;
}

function getPhoneOs() {
  try {
    return localStorage.getItem(PHONE_OS_KEY) || 'unknown';
  } catch (e) {
    return 'unknown';
  }
}

function setPhoneOs(os) {
  try {
    localStorage.setItem(PHONE_OS_KEY, os);
  } catch (e) {
    console.log('EZ SOS: failed to store phone OS');
  }
}

function markCompanionPresent() {
  try {
    localStorage.setItem(COMPANION_AT_KEY, String(Date.now()));
  } catch (e) {
    console.log('EZ SOS: failed to store companion presence');
  }
}

function isCompanionPresent() {
  try {
    var raw = localStorage.getItem(COMPANION_AT_KEY);
    if (!raw) {
      return false;
    }
    var at = parseInt(raw, 10);
    if (!at) {
      return false;
    }
    return Date.now() - at < COMPANION_FRESH_MS;
  } catch (e) {
    return false;
  }
}

function enabledContacts(settings) {
  return (settings.contacts || []).filter(function (c) {
    return c && c.enabled && c.phone && String(c.phone).trim().length > 0;
  });
}

function sendStatus(code) {
  Pebble.sendAppMessage(
    { STATUS: code },
    function () {
      console.log('EZ SOS: STATUS ' + code);
    },
    function () {
      console.log('EZ SOS: failed to send STATUS ' + code);
    }
  );
}

function trySilentSms(contacts, body) {
  if (!contacts || !body) {
    return false;
  }
  return false;
}

function buildMessageBody(prefix, lat, lon) {
  var latStr = String(lat);
  var lonStr = String(lon);
  return (
    prefix +
    '\nLat: ' + latStr + ', Lon: ' + lonStr +
    '\nhttps://maps.google.com/?q=' + latStr + ',' + lonStr
  );
}

function smsUrl(phone, body) {
  var cleaned = String(phone).replace(/\s+/g, '');
  return 'sms:' + cleaned + '?body=' + encodeURIComponent(body);
}

function openSmsFallback(contacts, body) {
  for (var i = 0; i < contacts.length; i++) {
    Pebble.openURL(smsUrl(contacts[i].phone, body));
  }
}

function installCompanionHtml() {
  var ver = APP_VERSION || '';
  var verLabel = ver ? ('v' + ver) : 'latest';
  return [
    '<!DOCTYPE html><html><head><meta charset="utf-8"/>',
    '<meta name="viewport" content="width=device-width,initial-scale=1"/>',
    '<title>Install EZ SOS</title>',
    '<style>body{font-family:sans-serif;margin:1.25rem;line-height:1.45;color:#111}',
    'a.btn{display:block;margin:.6rem 0;padding:.7rem 1rem;background:#0b57d0;color:#fff;',
    'text-decoration:none;border-radius:6px;text-align:center}',
    'a.btn.secondary{background:#eee;color:#111}</style></head><body>',
    '<h1>Install the EZ SOS companion</h1>',
    '<p>On Android, safety contacts and silent SMS live in the <strong>EZ SOS</strong> companion app.</p>',
    '<p><a class="btn" href="' + companionApkUrl(ver) + '">Download companion APK (' + verLabel + ')</a></p>',
    '<p><a class="btn secondary" href="' + releaseTagUrl(ver) + '">Open release page (' + verLabel + ')</a></p>',
    '<p><a class="btn secondary" href="' + RELEASES_LATEST + '">Latest release on GitHub</a></p>',
    '<p><a class="btn secondary" href="' + companionSettingsIntentUrl() + '">Already installed? Open settings</a></p>',
    '<p>If the versioned APK 404s, use Latest. After install, open the companion and grant permissions.</p>',
    '<p><button type="button" id="done">Done</button></p>',
    '<script>',
    'document.getElementById("done").onclick=function(){',
    '  if(typeof Pebble!=="undefined"&&Pebble.close){Pebble.close();}',
    '  else if(window.close){window.close();}',
    '};',
    '</script></body></html>'
  ].join('');
}

function companionSettingsHtml() {
  return [
    '<!DOCTYPE html><html><head><meta charset="utf-8"/>',
    '<meta name="viewport" content="width=device-width,initial-scale=1"/>',
    '<title>EZ SOS companion</title>',
    '<style>body{font-family:sans-serif;margin:1.25rem;line-height:1.45}',
    'a.btn{display:block;margin:.6rem 0;padding:.7rem 1rem;background:#0b57d0;color:#fff;',
    'text-decoration:none;border-radius:6px;text-align:center}',
    'a.btn.secondary{background:#eee;color:#111}</style></head><body>',
    '<h1>Use the EZ SOS companion</h1>',
    '<p>Contacts and SOS options are configured in the Android companion app.</p>',
    '<p><a class="btn" href="' + companionSettingsIntentUrl() + '">Open companion settings</a></p>',
    '<p><a class="btn secondary" href="ezsos://settings">Open settings (alternate)</a></p>',
    '<p><a class="btn secondary" href="' + releaseTagUrl(APP_VERSION) + '">Companion release</a></p>',
    '<p><button type="button" id="done">Done</button></p>',
    '<script>',
    'document.getElementById("done").onclick=function(){',
    '  if(typeof Pebble!=="undefined"&&Pebble.close){Pebble.close();}',
    '  else if(window.close){window.close();}',
    '};',
    '</script></body></html>'
  ].join('');
}

function openDataHtml(html) {
  var url = 'data:text/html;charset=utf-8,' + encodeURIComponent(html);
  Pebble.openURL(url);
}

function openInstallCompanionPage() {
  openDataHtml(installCompanionHtml());
}

function splitChunks(text, size) {
  var chunks = [];
  for (var i = 0; i < text.length; i += size) {
    chunks.push(text.substring(i, i + size));
  }
  if (!chunks.length) {
    chunks.push('');
  }
  return chunks;
}

function sendSettingsToWatch(settings, done) {
  var normalized = normalizeSettings(settings);
  var json = JSON.stringify(normalized);
  var chunks = splitChunks(json, CHUNK_SIZE);
  var count = chunks.length;
  var index = 0;

  function sendNext() {
    if (index >= count) {
      if (done) {
        done(true);
      }
      return;
    }

    var payload = {
      SETTINGS_CHUNK_INDEX: index,
      SETTINGS_CHUNK_DATA: chunks[index],
      SETTINGS_CHUNK_COUNT: count
    };
    if (index === 0) {
      payload.TRIGGER_MODE = normalized.triggerMode;
      payload.HOLD_MS = normalized.holdMs;
      payload.WATCH_ALARM_SOUND = normalized.watchAlarmSound ? 1 : 0;
    }

    Pebble.sendAppMessage(
      payload,
      function () {
        index += 1;
        sendNext();
      },
      function () {
        console.log('EZ SOS: settings chunk send failed at ' + index);
        if (done) {
          done(false);
        }
      }
    );
  }

  sendNext();
}

function handleSosRequest() {
  if (isCompanionPresent()) {
    // Core often delivers watch→phone AppMessages only to PKJS (or Kit2 bind fails).
    // Hand off to the companion via deep link so silent SMS still runs.
    console.log('EZ SOS: companion present — open ezsos://sos');
    Pebble.openURL('ezsos://sos');
    return;
  }

  var os = getPhoneOs();
  if (os === 'android') {
    console.log('EZ SOS: Android without companion — prompt install');
    openInstallCompanionPage();
    sendStatus('check_phone');
    return;
  }

  // Emulator / unknown / non-Android: localStorage contacts + sms: fallback
  var settings = loadSettings();
  var contacts = enabledContacts(settings);
  if (!contacts.length) {
    sendStatus('no_contacts');
    return;
  }

  if (!navigator.geolocation || !navigator.geolocation.getCurrentPosition) {
    sendStatus('no_gps');
    return;
  }

  navigator.geolocation.getCurrentPosition(
    function (pos) {
      var lat = pos.coords.latitude;
      var lon = pos.coords.longitude;
      var body = buildMessageBody(settings.messagePrefix, lat, lon);

      if (trySilentSms(contacts, body)) {
        sendStatus('sent');
        return;
      }

      openSmsFallback(contacts, body);
      sendStatus('check_phone');
    },
    function (err) {
      console.log('EZ SOS: geolocation failed: ' + (err && err.message ? err.message : err));
      sendStatus('no_gps');
    },
    {
      enableHighAccuracy: true,
      maximumAge: 0,
      timeout: 15000
    }
  );
}

function openConfiguration() {
  var settings = loadSettings();
  var meta = {
    settings: settings,
    phoneOs: getPhoneOs(),
    companionPresent: isCompanionPresent(),
    appVersion: APP_VERSION
  };
  var hash = encodeURIComponent(JSON.stringify(meta));
  var url =
    'data:text/html;charset=utf-8,' +
    encodeURIComponent(configHtml) +
    '#' +
    hash;
  Pebble.openURL(url);
}

Pebble.addEventListener('ready', function () {
  console.log('EZ SOS PKJS ready');
  // Emulator path still seeds watch from localStorage; Android companion overwrites when present.
  if (!isCompanionPresent()) {
    sendSettingsToWatch(loadSettings());
  }
});

Pebble.addEventListener('showConfiguration', function () {
  openConfiguration();
});

Pebble.addEventListener('webviewclosed', function (e) {
  if (!e || !e.response) {
    console.log('EZ SOS: configuration cancelled');
    return;
  }
  try {
    var parsed = JSON.parse(decodeURIComponent(e.response));
    if (parsed.phoneOs) {
      setPhoneOs(parsed.phoneOs);
    }
    if (parsed.openCompanion) {
      // Prefer opening from the config page links; this is a fallback if an old
      // close payload still requests it. intent:// is more reliable than ezsos:// alone.
      Pebble.openURL(companionSettingsIntentUrl());
      return;
    }
    if (parsed.openInstall) {
      openInstallCompanionPage();
      return;
    }
    // Full editor save (emulator / non-Android)
    if (parsed.settings) {
      var settings = saveSettings(parsed.settings);
      sendSettingsToWatch(settings);
    } else if (parsed.triggerMode || parsed.contacts) {
      var settingsLegacy = saveSettings(parsed);
      sendSettingsToWatch(settingsLegacy);
    }
  } catch (err) {
    console.log('EZ SOS: failed to parse configuration: ' + err);
  }
});

Pebble.addEventListener('appmessage', function (e) {
  var payload = (e && e.payload) || {};
  if (payload.COMPANION_PRESENT != null) {
    markCompanionPresent();
    console.log('EZ SOS: companion present');
  }
  if (payload.SOS_REQUEST != null) {
    handleSosRequest();
  }
});
