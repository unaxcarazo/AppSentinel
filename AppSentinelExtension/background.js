// ============================================================
//  AppSentinel Bridge — background.js (Service Worker MV3)
// ============================================================

const WS_URL = 'ws://localhost:8080';

let ws = null;
let reconnectTimer = null;
let lastSentKey = '';

// ------------------------------------------------------------
// 1. KEEPALIVE — evita que Chrome suspenda el Service Worker
// ------------------------------------------------------------
chrome.alarms.create('keepAlive', { periodInMinutes: 0.4 }); // cada ~24s

chrome.alarms.onAlarm.addListener((alarm) => {
    if (alarm.name === 'keepAlive') {
        if (!ws || ws.readyState === WebSocket.CLOSED) {
            console.log('[AppSentinel] 🔁 KeepAlive: reconectando WS...');
            connect();
        }
    }
});

// ------------------------------------------------------------
// 2. WEBSOCKET — conexión con el backend Java
// ------------------------------------------------------------
function connect() {
    // No abrir si ya está conectando o conectado
    if (ws && (ws.readyState === WebSocket.CONNECTING || ws.readyState === WebSocket.OPEN)) {
        return;
    }

    console.log('[AppSentinel] Conectando a', WS_URL);
    ws = new WebSocket(WS_URL);

    ws.onopen = () => {
        console.log('[AppSentinel] ✅ Conectado al backend Java');
        if (reconnectTimer) {
            clearInterval(reconnectTimer);
            reconnectTimer = null;
        }
    };

    ws.onmessage = (event) => {
        try {
            const msg = JSON.parse(event.data);
            console.log('[AppSentinel] 📨 Orden recibida:', msg);
            handleServerMessage(msg);
        } catch (e) {
            console.error('[AppSentinel] ❌ Error parseando mensaje del servidor:', e);
        }
    };

    ws.onerror = (err) => {
        console.error('[AppSentinel] ❌ Error WS:', err);
    };

    ws.onclose = (event) => {
        console.log(`[AppSentinel] 🔌 Desconectado. Código: ${event.code} | Razón: ${event.reason}`);
        ws = null;
        // Reintentar cada 3s si no hay ya un timer activo
        if (!reconnectTimer) {
            reconnectTimer = setInterval(connect, 3000);
        }
    };
}

// ------------------------------------------------------------
// 3. ÓRDENES DEL SERVIDOR
// ------------------------------------------------------------
function handleServerMessage(msg) {
    // Orden de cierre de pestaña
    if (msg.accion === 'cerrar_pestana' && msg.tabId) {
        chrome.tabs.remove(msg.tabId, () => {
            if (chrome.runtime.lastError) {
                console.error('[AppSentinel] ❌ Error cerrando pestaña:', chrome.runtime.lastError.message);
            } else {
                console.log('[AppSentinel] ✅ Pestaña cerrada:', msg.tabId);
            }
        });
    }
}

// ------------------------------------------------------------
// 4. ENVÍO DE INFO AL BACKEND
// ------------------------------------------------------------

// URLs internas que no deben enviarse al backend
const BLOCKED_PREFIXES = [
    'chrome://',
    'chrome-extension://',
    'about:',
    'edge://',
    'devtools://',
];

function isValidUrl(url) {
    if (!url) return false;
    return !BLOCKED_PREFIXES.some(prefix => url.startsWith(prefix));
}

function sendTabInfo(tabId, url, title) {
    // Filtrar URLs internas del navegador
    if (!isValidUrl(url)) return;

    // Verificar conexión
    if (!ws || ws.readyState !== WebSocket.OPEN) {
        console.warn('[AppSentinel] ⚠️ WS no conectado, ignorando:', url);
        return;
    }

    // Deduplicación: no reenviar si URL + tabId no han cambiado
    const key = `${tabId}|${url}`;
    if (key === lastSentKey) return;
    lastSentKey = key;

    const payload = {
        url:    url          || '',
        titulo: title        || 'Sin título',
        tabId:  tabId
    };

    try {
        ws.send(JSON.stringify(payload));
        console.log('[AppSentinel] 📤 Enviado:', payload);
    } catch (e) {
        console.error('[AppSentinel] ❌ Error enviando:', e);
    }
}

// ------------------------------------------------------------
// 5. EVENTOS DE CHROME
// ------------------------------------------------------------

// Cambio de pestaña activa
chrome.tabs.onActivated.addListener((activeInfo) => {
    chrome.tabs.get(activeInfo.tabId, (tab) => {
        if (chrome.runtime.lastError) return;
        sendTabInfo(tab.id, tab.url, tab.title);
    });
});

// Actualización de URL o título en la pestaña activa (listener unificado)
chrome.tabs.onUpdated.addListener((tabId, changeInfo, tab) => {
    if (!tab.active) return; // Solo nos interesa la pestaña visible

    if (changeInfo.url) {
        // Navegación a nueva URL
        sendTabInfo(tabId, changeInfo.url, tab.title);
    } else if (changeInfo.title && tab.url) {
        // SPA que cambia el título sin cambiar la URL
        sendTabInfo(tabId, tab.url, changeInfo.title);
    }
});

// ------------------------------------------------------------
// 6. INICIO
// ------------------------------------------------------------
connect();