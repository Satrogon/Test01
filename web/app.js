(() => {
  'use strict';

  const CHAT_SALT = new TextEncoder().encode('ChatPrivado-v1');
  const KEY_DERIVATION_ITERATIONS = 200_000;
  const NONCE_LENGTH = 12;
  const encoder = new TextEncoder();
  const decoder = new TextDecoder();

  const elements = {
    setupForm: document.getElementById('setup-form'),
    conversation: document.getElementById('conversation'),
    nameInput: document.getElementById('name-input'),
    keyInput: document.getElementById('key-input'),
    joinButton: document.getElementById('join-button'),
    messages: document.getElementById('messages'),
    composerForm: document.getElementById('composer-form'),
    messageInput: document.getElementById('message-input'),
    sendButton: document.getElementById('send-button'),
    status: document.getElementById('status-message')
  };

  let chatKey;
  let participantName;
  let cursor = 0;
  let connected = false;

  function encodeBase64(bytes) {
    let binary = '';
    for (const byte of bytes) {
      binary += String.fromCharCode(byte);
    }
    return btoa(binary);
  }

  function decodeBase64(value) {
    const binary = atob(value);
    return Uint8Array.from(binary, character => character.charCodeAt(0));
  }

  async function deriveChatKey(password) {
    const passwordMaterial = await crypto.subtle.importKey(
      'raw',
      encoder.encode(password),
      'PBKDF2',
      false,
      ['deriveKey']
    );

    return crypto.subtle.deriveKey(
      {
        name: 'PBKDF2',
        salt: CHAT_SALT,
        iterations: KEY_DERIVATION_ITERATIONS,
        hash: 'SHA-256'
      },
      passwordMaterial,
      { name: 'AES-GCM', length: 128 },
      false,
      ['encrypt', 'decrypt']
    );
  }

  async function encryptMessage(plainText) {
    const nonce = crypto.getRandomValues(new Uint8Array(NONCE_LENGTH));
    const cipherText = new Uint8Array(await crypto.subtle.encrypt(
      { name: 'AES-GCM', iv: nonce },
      chatKey,
      encoder.encode(plainText)
    ));
    const packet = new Uint8Array(nonce.length + cipherText.length);
    packet.set(nonce);
    packet.set(cipherText, nonce.length);
    return encodeBase64(packet);
  }

  async function decryptMessage(packetBase64) {
    const packet = decodeBase64(packetBase64);
    if (packet.length <= NONCE_LENGTH) {
      throw new Error('Paquete incompleto');
    }

    const nonce = packet.slice(0, NONCE_LENGTH);
    const cipherText = packet.slice(NONCE_LENGTH);
    const plainText = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv: nonce },
      chatKey,
      cipherText
    );
    return decoder.decode(plainText);
  }

  function showMessage(text, type = '') {
    const message = document.createElement('div');
    message.className = `bubble ${type}`.trim();
    message.textContent = text;
    elements.messages.appendChild(message);
    elements.messages.scrollTop = elements.messages.scrollHeight;
  }

  async function readNewMessages() {
    const response = await fetch(`/api/messages?after=${cursor}`, { cache: 'no-store' });
    if (!response.ok) {
      throw new Error(`El servidor respondió ${response.status}`);
    }

    const rows = (await response.text()).split('\n');
    for (const row of rows) {
      if (!row) continue;

      const separator = row.indexOf('\t');
      if (separator < 1) continue;

      const messageId = Number(row.slice(0, separator));
      const line = decoder.decode(decodeBase64(row.slice(separator + 1)));
      cursor = Math.max(cursor, messageId);

      if (line.startsWith('MSG:')) {
        await showEncryptedMessage(line.slice(4));
      } else if (line.startsWith('SYS:')) {
        showMessage(line.slice(4), 'system');
      }
    }
  }

  async function showEncryptedMessage(encryptedText) {
    try {
      const message = await decryptMessage(encryptedText);
      const type = message.startsWith(`${participantName}: `) ? 'mine' : '';
      showMessage(message, type);
    } catch (error) {
      showMessage('Mensaje ilegible: comprueba que todos usan la misma clave.', 'system');
    }
  }

  async function pollForMessages() {
    while (connected) {
      try {
        await readNewMessages();
        elements.status.textContent = '';
      } catch (error) {
        elements.status.textContent = 'Conexión interrumpida. Intentando reconectar…';
        await new Promise(resolve => window.setTimeout(resolve, 1800));
      }
    }
  }

  async function joinChat(event) {
    event.preventDefault();
    elements.joinButton.disabled = true;
    elements.status.textContent = '';

    try {
      participantName = elements.nameInput.value.trim();
      const sharedPassword = elements.keyInput.value;
      if (!participantName || !sharedPassword) {
        throw new Error('Escribe tu nombre y la clave secreta.');
      }
      if (!window.isSecureContext || !window.crypto?.subtle) {
        throw new Error('Abre el chat con la dirección HTTPS de Tailscale Serve.');
      }

      const response = await fetch('/api/cursor', { cache: 'no-store' });
      if (!response.ok) {
        throw new Error('No se pudo contactar con el servidor del chat.');
      }

      cursor = Number(await response.text()) || 0;
      chatKey = await deriveChatKey(sharedPassword);
      elements.keyInput.value = '';
      connected = true;
      elements.setupForm.classList.add('hidden');
      elements.conversation.classList.remove('hidden');
      elements.messageInput.focus();
      void pollForMessages();
    } catch (error) {
      elements.status.textContent = error.message || 'No se pudo entrar al chat.';
      elements.joinButton.disabled = false;
    }
  }

  async function sendMessage(event) {
    event.preventDefault();
    const messageText = elements.messageInput.value.trim();
    if (!messageText) return;

    elements.sendButton.disabled = true;
    try {
      const encryptedText = await encryptMessage(`${participantName}: ${messageText}`);
      const response = await fetch('/api/send', {
        method: 'POST',
        headers: { 'Content-Type': 'text/plain; charset=utf-8' },
        body: `MSG:${encryptedText}`
      });
      if (!response.ok) {
        throw new Error('No se pudo enviar el mensaje.');
      }

      elements.messageInput.value = '';
      elements.messageInput.focus();
      elements.status.textContent = '';
    } catch (error) {
      elements.status.textContent = error.message || 'No se pudo enviar el mensaje.';
    } finally {
      elements.sendButton.disabled = false;
    }
  }

  elements.setupForm.addEventListener('submit', joinChat);
  elements.composerForm.addEventListener('submit', sendMessage);
})();
