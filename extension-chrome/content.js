const FFMPEG_ICON_SVG = `<svg class="ffmpeg-float-icon" viewBox="0 0 24 24" style="width:14px;height:14px;fill:currentColor;margin-right:6px;"><path d="M17 10.5V7c0-.55-.45-1-1-1H4c-.55 0-1 .45-1 1v10c0 .55.45 1 1 1h12c.55 0 1-.45 1-1v-3.5l4 4v-11l-4 4zM14 13h-3v3H9v-3H6v-2h3V8h2v3h3v2z"/></svg>`;

let detectedStreams = [];

// Inject stylesheet if not already present
function injectStyles() {
  if (document.getElementById('ffmpeg-styles')) return;
  const style = document.createElement('style');
  style.id = 'ffmpeg-styles';
  style.textContent = `
    .ffmpeg-float-panel {
      position: absolute !important;
      top: 10px !important;
      right: 10px !important;
      z-index: 2147483647 !important;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif !important;
      direction: ltr !important;
      user-select: none !important;
      pointer-events: none !important; /* Allow clicks around the button to pass through */
    }
    
    .ffmpeg-float-btn {
      display: flex !important;
      align-items: center !important;
      background: linear-gradient(135deg, #7c4dff, #651fff) !important;
      color: white !important;
      border: none !important;
      border-radius: 6px !important;
      padding: 6px 10px !important;
      font-size: 12px !important;
      font-weight: 600 !important;
      cursor: pointer !important;
      box-shadow: 0 4px 12px rgba(124, 77, 255, 0.4) !important;
      transition: all 0.2s ease-in-out !important;
      outline: none !important;
      pointer-events: auto !important; /* Intercept clicks on the button */
      z-index: 2147483647 !important;
    }
    
    .ffmpeg-float-btn:hover {
      background: linear-gradient(135deg, #651fff, #7c4dff) !important;
      box-shadow: 0 6px 16px rgba(124, 77, 255, 0.6) !important;
      transform: translateY(-1px) !important;
    }
    
    .ffmpeg-dropdown-menu {
      display: none !important;
      position: absolute !important;
      top: 100% !important;
      right: 0 !important;
      background-color: #161622 !important;
      border: 1px solid #2e2e3e !important;
      border-radius: 8px !important;
      min-width: 240px !important;
      box-shadow: 0 10px 25px rgba(0, 0, 0, 0.5) !important;
      overflow: hidden !important;
      animation: ffmpegFadeIn 0.15s ease-out !important;
      padding: 6px 0 !important;
      pointer-events: auto !important; /* Intercept clicks on the dropdown */
      z-index: 2147483647 !important;
    }
    
    @keyframes ffmpegFadeIn {
      from { opacity: 0; transform: translateY(-5px); }
      to { opacity: 1; transform: translateY(0); }
    }
    
    .ffmpeg-float-panel:hover .ffmpeg-dropdown-menu {
      display: block !important;
    }
    
    .ffmpeg-dropdown-header {
      padding: 6px 12px !important;
      font-size: 10px !important;
      text-transform: uppercase !important;
      color: #8f8fbf !important;
      letter-spacing: 0.5px !important;
      border-bottom: 1px solid #2e2e3e !important;
      margin-bottom: 4px !important;
      font-weight: 700 !important;
    }
    
    .ffmpeg-dropdown-item {
      display: flex !important;
      align-items: center !important;
      justify-content: space-between !important;
      padding: 8px 12px !important;
      gap: 12px !important;
      transition: background-color 0.2s !important;
      border-bottom: 1px solid rgba(255, 255, 255, 0.03) !important;
    }
    
    .ffmpeg-dropdown-item:last-child {
      border-bottom: none !important;
    }
    
    .ffmpeg-dropdown-item:hover {
      background-color: #20202e !important;
    }
    
    .ffmpeg-stream-info {
      display: flex !important;
      flex-direction: column !important;
      flex-grow: 1 !important;
      max-width: 140px !important;
      text-align: left !important;
    }
    
    .ffmpeg-stream-quality {
      font-size: 11px !important;
      font-weight: 600 !important;
      color: #e0e0ff !important;
      white-space: nowrap !important;
      overflow: hidden !important;
      text-overflow: ellipsis !important;
    }
    
    .ffmpeg-stream-title {
      font-size: 9px !important;
      color: #8f8fbf !important;
      white-space: nowrap !important;
      overflow: hidden !important;
      text-overflow: ellipsis !important;
      margin-top: 2px !important;
    }
    
    .ffmpeg-actions {
      display: flex !important;
      gap: 4px !important;
    }
    
    .ffmpeg-action-btn {
      background-color: #7c4dff !important;
      color: white !important;
      border: none !important;
      border-radius: 4px !important;
      padding: 4px 8px !important;
      font-size: 10px !important;
      font-weight: 600 !important;
      cursor: pointer !important;
      transition: all 0.2s !important;
    }
    
    .ffmpeg-action-btn:hover {
      background-color: #651fff !important;
    }
    
    .ffmpeg-action-btn.ffmpeg-play {
      background-color: #00e676 !important;
    }
    
    .ffmpeg-action-btn.ffmpeg-play:hover {
      background-color: #00c853 !important;
    }
    
    .ffmpeg-no-streams {
      padding: 12px !important;
      font-size: 11px !important;
      color: #8f8fbf !important;
      text-align: center !important;
    }
    
    .ffmpeg-status-toast {
      position: fixed !important;
      bottom: 24px !important;
      right: 24px !important;
      z-index: 2147483647 !important;
      padding: 12px 20px !important;
      border-radius: 8px !important;
      color: white !important;
      font-size: 13px !important;
      font-weight: 600 !important;
      box-shadow: 0 10px 30px rgba(0, 0, 0, 0.4) !important;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif !important;
      animation: ffmpegSlideIn 0.3s cubic-bezier(0.18, 0.89, 0.32, 1.28) !important;
    }
    
    @keyframes ffmpegSlideIn {
      from { transform: translateY(50px); opacity: 0; }
      to { transform: translateY(0); opacity: 1; }
    }
  `;
  document.head.appendChild(style);
}

// Show a success/error message in the corner of the page
function showToast(message, isSuccess = true) {
  // Remove existing toasts
  const existing = document.querySelectorAll('.ffmpeg-status-toast');
  existing.forEach(t => t.remove());

  const toast = document.createElement('div');
  toast.className = 'ffmpeg-status-toast';
  toast.style.backgroundColor = isSuccess ? 'rgba(11, 19, 15, 0.95)' : 'rgba(28, 13, 16, 0.95)';
  toast.style.color = isSuccess ? '#00e676' : '#ff1744';
  toast.style.border = isSuccess ? '1px solid #00e676' : '1px solid #ff1744';
  toast.style.boxShadow = isSuccess ? '0 4px 16px rgba(0, 230, 118, 0.2)' : '0 4px 16px rgba(255, 23, 68, 0.2)';
  toast.textContent = message;
  document.body.appendChild(toast);
  
  setTimeout(() => {
    toast.style.transition = 'opacity 0.4s ease-out, transform 0.4s ease-out';
    toast.style.opacity = '0';
    toast.style.transform = 'translateY(10px)';
    setTimeout(() => toast.remove(), 400);
  }, 3000);
}

// Update the dropdown listing of streams
function updateDropdown(dropdown) {
  dropdown.innerHTML = '';
  
  const header = document.createElement('div');
  header.className = 'ffmpeg-dropdown-header';
  header.textContent = 'Flux vidéo détectés';
  dropdown.appendChild(header);
  
  if (!detectedStreams || detectedStreams.length === 0) {
    const empty = document.createElement('div');
    empty.className = 'ffmpeg-no-streams';
    empty.textContent = 'Aucun flux capturé (Lancez la vidéo)';
    dropdown.appendChild(empty);
    return;
  }
  
  detectedStreams.forEach((stream) => {
    const item = document.createElement('div');
    item.className = 'ffmpeg-dropdown-item';
    
    const info = document.createElement('div');
    info.className = 'ffmpeg-stream-info';
    
    const quality = document.createElement('div');
    quality.className = 'ffmpeg-stream-quality';
    quality.textContent = stream.quality;
    quality.title = stream.quality;
    info.appendChild(quality);
    
    const title = document.createElement('div');
    title.className = 'ffmpeg-stream-title';
    title.textContent = stream.title || 'Flux vidéo';
    title.title = stream.title || '';
    info.appendChild(title);
    
    item.appendChild(info);
    
    const actions = document.createElement('div');
    actions.className = 'ffmpeg-actions';
    
    const dlBtn = document.createElement('button');
    dlBtn.className = 'ffmpeg-action-btn';
    dlBtn.textContent = 'Télécharger';
    dlBtn.title = 'Télécharger avec options';
    dlBtn.addEventListener('click', () => {
      showWebDownloadPanel(stream.url, stream.title || '');
    });
    
    const playBtn = document.createElement('button');
    playBtn.className = 'ffmpeg-action-btn ffmpeg-play';
    playBtn.textContent = 'Lire';
    playBtn.title = 'Lancer la lecture dans le streaming player';
    playBtn.addEventListener('click', () => {
      chrome.runtime.sendMessage({
        action: 'sendToApp',
        url: stream.url,
        title: stream.title || '',
        play: true
      }, (response) => {
        if (response && response.status === 'ok') {
          showToast('▶ Lecture lancée dans FFmpeg Studio !');
        } else {
          showToast('⚠ Erreur. Vérifiez que FFmpeg Studio est ouvert.', false);
        }
      });
    });
    
    actions.appendChild(dlBtn);
    actions.appendChild(playBtn);
    item.appendChild(actions);
    
    dropdown.appendChild(item);
  });
}

function updateAllPanels() {
  const panels = document.querySelectorAll('.ffmpeg-float-panel');
  const show = (detectedStreams && detectedStreams.length > 0);
  panels.forEach(panel => {
    panel.style.display = show ? 'block' : 'none';
    const dropdown = panel.querySelector('.ffmpeg-dropdown-menu');
    if (dropdown) {
      updateDropdown(dropdown);
    }
  });
}

// Create overlay buttons on videos
function createFloatPanel(video) {
  const panel = document.createElement('div');
  panel.className = 'ffmpeg-float-panel';
  
  const btn = document.createElement('button');
  btn.className = 'ffmpeg-float-btn';
  btn.innerHTML = FFMPEG_ICON_SVG + '<span>Télécharger</span>';
  panel.appendChild(btn);
  
  const dropdown = document.createElement('div');
  dropdown.className = 'ffmpeg-dropdown-menu';
  panel.appendChild(dropdown);
  
  // Stop mouse clicks/drags from propagating to player
  panel.addEventListener('click', (e) => e.stopPropagation());
  panel.addEventListener('dblclick', (e) => e.stopPropagation());
  panel.addEventListener('mousedown', (e) => e.stopPropagation());
  panel.addEventListener('mouseup', (e) => e.stopPropagation());
  panel.addEventListener('keydown', (e) => e.stopPropagation());
  
  updateDropdown(dropdown);
  
  return panel;
}

// Trouver le conteneur principal du lecteur vidéo en remontant le DOM
// tant que la taille reste similaire à celle de la vidéo (pour englober les overlays publicitaires/contrôles)
function getPlayerContainer(video) {
  let current = video.parentElement;
  if (!current) return null;
  
  const videoWidth = video.offsetWidth;
  const videoHeight = video.offsetHeight;
  let playerContainer = current;
  
  while (current && current !== document.body && current !== document.documentElement) {
    const w = current.offsetWidth;
    const h = current.offsetHeight;
    
    // Si l'ancêtre a des dimensions similaires (marge de 50px pour tolérer les barres de contrôle/bordures)
    if (Math.abs(w - videoWidth) <= 50 && Math.abs(h - videoHeight) <= 50) {
      playerContainer = current;
    } else {
      break;
    }
    current = current.parentElement;
  }
  return playerContainer;
}

let extensionEnabled = true;

function checkEnabledState() {
  chrome.storage.local.get({ enabled: true }, (result) => {
    extensionEnabled = result.enabled;
    if (!extensionEnabled) {
      removeAllPanels();
    } else {
      scanForVideos();
    }
  });
}

chrome.storage.onChanged.addListener((changes, area) => {
  if (area === "local" && changes.enabled) {
    extensionEnabled = changes.enabled.newValue;
    if (!extensionEnabled) {
      removeAllPanels();
    } else {
      scanForVideos();
    }
  }
});

// Appeler initialement
checkEnabledState();

function removeAllPanels() {
  const videos = document.getElementsByTagName('video');
  for (let i = 0; i < videos.length; i++) {
    const video = videos[i];
    if (video.__ffmpegPanel) {
      delete video.__ffmpegPanel;
    }
  }
  const panels = document.querySelectorAll('.ffmpeg-float-panel');
  panels.forEach((panel) => {
    if (panel.parentElement) {
      panel.parentElement.removeChild(panel);
    }
  });
  const modal = document.getElementById('ffmpeg-download-modal');
  if (modal) {
    document.body.removeChild(modal);
  }
}

function decodeHtmlEntities(str) {
  if (!str) return str;
  const temp = document.createElement("textarea");
  temp.innerHTML = str;
  return temp.value;
}

function scanForDataSources() {
  const elements = document.querySelectorAll('[data-sources]');
  elements.forEach(el => {
    try {
      let dataSourcesStr = el.getAttribute('data-sources');
      if (dataSourcesStr) {
        if (dataSourcesStr.includes('&quot;') || dataSourcesStr.includes('&amp;')) {
          dataSourcesStr = decodeHtmlEntities(dataSourcesStr);
        }
        const sources = JSON.parse(dataSourcesStr);
        if (Array.isArray(sources)) {
          sources.forEach(source => {
            if (source.src) {
              chrome.runtime.sendMessage({
                action: "addDetectedStreamFromDOM",
                url: source.src
              }).catch(() => {});
            }
          });
        }
      }
    } catch (e) {
      // Ignorer
    }
  });
}

function scanForJsonLd() {
  const scripts = document.querySelectorAll('script[type="application/ld+json"]');
  scripts.forEach(script => {
    try {
      const text = script.textContent;
      if (text && text.includes("licdn.com")) {
        // Extraire toutes les URLs dms.licdn.com ou media.licdn.com
        const matches = text.match(/https?:\/\/[^\s"']+/g);
        if (matches) {
          matches.forEach(url => {
            const cleanUrl = url.replace(/\\/g, '');
            if (cleanUrl.includes("licdn.com/")) {
              chrome.runtime.sendMessage({
                action: "addDetectedStreamFromDOM",
                url: cleanUrl
              }).catch(() => {});
            }
          });
        }
      }
    } catch (e) {
      // Ignorer
    }
  });
}

function scanForMetaTags() {
  const metas = document.querySelectorAll('meta[property^="og:video"], meta[name^="twitter:player"]');
  metas.forEach(meta => {
    try {
      const content = meta.getAttribute('content');
      if (content && content.includes("licdn.com/")) {
        chrome.runtime.sendMessage({
          action: "addDetectedStreamFromDOM",
          url: content
        }).catch(() => {});
      }
    } catch (e) {
      // Ignorer
    }
  });
}

// Find HTML5 video players on the page and append the button to their parent wrapper
function scanForVideos() {
  if (!extensionEnabled) {
    removeAllPanels();
    return;
  }
  injectStyles();
  scanForDataSources();
  scanForJsonLd();
  scanForMetaTags();
  const videos = document.getElementsByTagName('video');
  for (let i = 0; i < videos.length; i++) {
    const video = videos[i];
    
    // Ignore small layout videos (like badges or tiny avatars)
    if (video.offsetWidth < 150 || video.offsetHeight < 100) continue;
    
    const parent = getPlayerContainer(video);
    if (!parent) continue;
    
    // Si un conteneur d'overlay comme .UhUzCf ou .Wkyg5e existe dans le parent, on l'utilise
    // comme parent direct pour notre bouton flottant afin de rester au-dessus et d'être cliquable.
    const overlay = parent.querySelector('.UhUzCf, .Wkyg5e');
    const targetParent = overlay || parent;
    
    let panel = video.__ffmpegPanel;
    if (!panel || !document.body.contains(panel)) {
      // Style parent to relative if static so absolute children align correctly
      const style = window.getComputedStyle(targetParent);
      if (style.position === 'static') {
        targetParent.style.position = 'relative';
      }
      
      panel = createFloatPanel(video);
      video.__ffmpegPanel = panel;
      targetParent.appendChild(panel);
    } else {
      // S'assurer que le panel est toujours dans le bon parent et en dernier enfant (au-dessus des overlays)
      if (panel.parentElement !== targetParent) {
        targetParent.appendChild(panel);
      } else if (targetParent.lastChild !== panel) {
        targetParent.appendChild(panel);
      }
    }
    
    const show = (detectedStreams && detectedStreams.length > 0);
    panel.style.display = show ? 'block' : 'none';
  }
}

// Request stream listings on startup
chrome.runtime.sendMessage({ action: "getStreams" }, (response) => {
  if (response && response.streams) {
    detectedStreams = response.streams;
    updateAllPanels();
  }
});

// Realtime listeners
chrome.runtime.onMessage.addListener((message) => {
  if (message.action === "streamsDetected" || message.action === "streamUpdated") {
    chrome.runtime.sendMessage({ action: "getStreams" }, (response) => {
      if (response && response.streams) {
        detectedStreams = response.streams;
        updateAllPanels();
      }
    });
  }
});

// Periodic scan & DOM Observer
setInterval(scanForVideos, 1500);

const observer = new MutationObserver(scanForVideos);
observer.observe(document.body, { childList: true, subtree: true });

// Initial scan
scanForVideos();

// Afficher le panel de téléchargement de type IDM directement sur la page web
function showWebDownloadPanel(url, title) {
  let modal = document.getElementById('ffmpeg-download-modal');
  if (modal) {
    document.body.removeChild(modal);
  }
  
  modal = document.createElement('div');
  modal.id = 'ffmpeg-download-modal';
  Object.assign(modal.style, {
    position: 'fixed',
    top: '0',
    left: '0',
    width: '100vw',
    height: '100vh',
    backgroundColor: 'rgba(0, 0, 0, 0.6)',
    backdropFilter: 'blur(4px)',
    zIndex: '2147483647',
    display: 'flex',
    justifyContent: 'center',
    alignItems: 'center',
    fontFamily: "'Segoe UI', system-ui, -apple-system, sans-serif"
  });
  
  const box = document.createElement('div');
  Object.assign(box.style, {
    width: '360px',
    backgroundColor: '#1a1a24',
    border: '1px solid #7c4dff',
    borderRadius: '8px',
    padding: '16px',
    boxShadow: '0 10px 25px rgba(0,0,0,0.5)',
    color: '#e1e1e6',
    display: 'flex',
    flexDirection: 'column',
    gap: '12px',
    boxSizing: 'border-box'
  });
  
  const header = document.createElement('div');
  Object.assign(header.style, {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    borderBottom: '1px solid #2b2b36',
    paddingBottom: '8px',
    fontWeight: 'bold',
    fontSize: '14px',
    color: '#ffffff'
  });
  header.innerHTML = '<span>📥 Options de Téléchargement</span>';
  
  const closeBtn = document.createElement('span');
  closeBtn.textContent = '×';
  Object.assign(closeBtn.style, {
    cursor: 'pointer',
    fontSize: '20px',
    fontWeight: 'bold',
    color: '#7a7a8a'
  });
  closeBtn.addEventListener('click', () => document.body.removeChild(modal));
  header.appendChild(closeBtn);
  box.appendChild(header);
  
  const createField = (labelText, value, isReadonly = false) => {
    const container = document.createElement('div');
    container.style.display = 'flex';
    container.style.flexDirection = 'column';
    container.style.gap = '4px';
    
    const label = document.createElement('label');
    Object.assign(label.style, {
      color: isReadonly ? '#7a7a8a' : '#e1e1e6',
      fontWeight: 'bold',
      fontSize: '10px',
      textTransform: 'uppercase'
    });
    label.textContent = labelText;
    container.appendChild(label);
    
    const input = document.createElement('input');
    input.type = 'text';
    input.value = value;
    if (isReadonly) input.readOnly = true;
    Object.assign(input.style, {
      width: '100%',
      boxSizing: 'border-box',
      backgroundColor: '#121214',
      border: '1px solid #2b2b36',
      color: isReadonly ? '#7a7a8a' : '#ffffff',
      padding: '8px',
      borderRadius: '4px',
      fontSize: '12px',
      fontFamily: isReadonly ? 'monospace' : 'inherit',
      outline: 'none'
    });
    if (!isReadonly) {
      input.addEventListener('focus', () => input.style.borderColor = '#7c4dff');
      input.addEventListener('blur', () => input.style.borderColor = '#2b2b36');
    }
    container.appendChild(input);
    return { container, input };
  };
  
  const urlField = createField('URL du flux', url, true);
  box.appendChild(urlField.container);
  
  const fileNameField = createField('Nom du fichier', '');
  fileNameField.input.placeholder = 'Chargement du nom...';
  box.appendChild(fileNameField.container);
  
  const destFolderField = createField('Dossier de destination', '', true);
  destFolderField.input.placeholder = 'Chargement du dossier...';
  const destInput = destFolderField.input;
  
  const destRow = document.createElement('div');
  Object.assign(destRow.style, {
    display: 'flex',
    gap: '8px',
    width: '100%',
    alignItems: 'center'
  });
  
  destInput.parentNode.insertBefore(destRow, destInput);
  destRow.appendChild(destInput);
  
  const browseBtn = document.createElement('button');
  browseBtn.textContent = 'Parcourir...';
  Object.assign(browseBtn.style, {
    backgroundColor: '#7c4dff',
    color: '#ffffff',
    border: 'none',
    borderRadius: '4px',
    padding: '7px 10px',
    cursor: 'pointer',
    fontSize: '11px',
    fontWeight: 'bold',
    whiteSpace: 'nowrap',
    boxSizing: 'border-box'
  });
  
  browseBtn.addEventListener('click', (e) => {
    e.preventDefault();
    browseBtn.disabled = true;
    browseBtn.textContent = 'Choix...';
    
    chrome.runtime.sendMessage({ action: 'pickDirectory' }, (data) => {
      browseBtn.disabled = false;
      browseBtn.textContent = 'Parcourir...';
      if (data && data.status === 'ok' && data.path) {
        destInput.value = data.path;
      } else {
        alert("Impossible d'ouvrir le sélecteur. Assurez-vous que FFmpeg Studio est lancé.");
      }
    });
  });
  destRow.appendChild(browseBtn);
  box.appendChild(destFolderField.container);
  
  const formatContainer = document.createElement('div');
  formatContainer.style.display = 'flex';
  formatContainer.style.flexDirection = 'column';
  formatContainer.style.gap = '4px';
  
  const formatLabel = document.createElement('label');
  Object.assign(formatLabel.style, {
    color: '#e1e1e6',
    fontWeight: 'bold',
    fontSize: '10px',
    textTransform: 'uppercase'
  });
  formatLabel.textContent = 'Format / Conversion';
  formatContainer.appendChild(formatLabel);
  
  const formatSelect = document.createElement('select');
  Object.assign(formatSelect.style, {
    width: '100%',
    boxSizing: 'border-box',
    backgroundColor: '#121214',
    border: '1px solid #2b2b36',
    color: '#ffffff',
    padding: '8px',
    borderRadius: '4px',
    fontSize: '12px',
    outline: 'none'
  });
  formatSelect.innerHTML = `
    <option value="mp4">Vidéo MP4 (Remux direct)</option>
    <option value="mp3">Audio MP3 (Extraction)</option>
  `;
  formatContainer.appendChild(formatSelect);
  box.appendChild(formatContainer);
  
  const buttonContainer = document.createElement('div');
  Object.assign(buttonContainer.style, {
    display: 'flex',
    justifyContent: 'flex-end',
    gap: '8px',
    marginTop: '8px'
  });
  
  const cancelBtn = document.createElement('button');
  cancelBtn.textContent = 'Annuler';
  Object.assign(cancelBtn.style, {
    backgroundColor: '#3b3b4a',
    color: '#ffffff',
    border: 'none',
    borderRadius: '4px',
    padding: '8px 16px',
    cursor: 'pointer',
    fontSize: '12px',
    fontWeight: 'bold'
  });
  cancelBtn.addEventListener('click', () => document.body.removeChild(modal));
  
  const startBtn = document.createElement('button');
  startBtn.textContent = 'Démarrer';
  Object.assign(startBtn.style, {
    backgroundColor: '#00e676',
    color: '#121214',
    border: 'none',
    borderRadius: '4px',
    padding: '8px 16px',
    cursor: 'pointer',
    fontSize: '12px',
    fontWeight: 'bold'
  });
  
  buttonContainer.appendChild(cancelBtn);
  buttonContainer.appendChild(startBtn);
  box.appendChild(buttonContainer);
  
  modal.appendChild(box);
  document.body.appendChild(modal);
  
  // Format change listener
  formatSelect.addEventListener('change', (e) => {
    const format = e.target.value;
    let currentName = fileNameField.input.value;
    if (format === 'mp3') {
      if (currentName.endsWith('.mp4')) {
        fileNameField.input.value = currentName.substring(0, currentName.length - 4) + '.mp3';
      } else if (!currentName.endsWith('.mp3')) {
        fileNameField.input.value = currentName + '.mp3';
      }
    } else {
      if (currentName.endsWith('.mp3')) {
        fileNameField.input.value = currentName.substring(0, currentName.length - 4) + '.mp4';
      } else if (!currentName.endsWith('.mp4')) {
        fileNameField.input.value = currentName + '.mp4';
      }
    }
  });
  
  // Fetch default path
  chrome.runtime.sendMessage({ action: 'getDefaultPath', title: title, preset: 'mp4' }, (data) => {
    if (data && data.status !== 'error') {
      fileNameField.input.value = data.fileName;
      destFolderField.input.value = data.defaultFolder;
    } else {
      const safeTitle = (title || "video").replace(/[\\/:*?"<>|]/g, "_").trim();
      fileNameField.input.value = (safeTitle ? safeTitle : "video") + ".mp4";
      destFolderField.input.value = "C:\\Users\\...\\Downloads";
      showToast('⚠ Lancez FFmpeg Studio pour charger le dossier par défaut.', false);
    }
  });
    
  // Start action listener
  startBtn.addEventListener('click', () => {
    const fileName = fileNameField.input.value.trim();
    const destFolder = destFolderField.input.value.trim();
    
    if (!fileName || fileName === 'Chargement...') {
      alert('Veuillez saisir un nom de fichier.');
      return;
    }
    if (!destFolder || destFolder === 'Chargement...') {
      alert('Veuillez saisir un dossier de destination.');
      return;
    }
    
    let sep = '\\';
    if (destFolder.includes('/')) {
      sep = '/';
    }
    const outputPath = destFolder + (destFolder.endsWith(sep) ? "" : sep) + fileName;
    
    let jobTitle = fileName;
    if (jobTitle.endsWith('.mp4') || jobTitle.endsWith('.mp3')) {
      jobTitle = jobTitle.substring(0, jobTitle.length - 4);
    }
    
    chrome.runtime.sendMessage({
      action: 'sendToApp',
      url: url,
      title: jobTitle,
      play: false,
      download: true,
      outputPath: outputPath
    }, (data) => {
      if (data && data.status === 'ok') {
        showToast('✓ Téléchargement démarré dans FFmpeg Studio !');
        document.body.removeChild(modal);
      } else {
        showToast('⚠ Assurez-vous que FFmpeg Studio est ouvert.', false);
      }
    });
  });
}
