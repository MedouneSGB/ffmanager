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
    }
    
    .ffmpeg-float-btn:hover {
      background: linear-gradient(135deg, #651fff, #7c4dff) !important;
      box-shadow: 0 6px 16px rgba(124, 77, 255, 0.6) !important;
      transform: translateY(-1px) !important;
    }
    
    .ffmpeg-dropdown-menu {
      display: none !important;
      position: absolute !important;
      top: calc(100% + 6px) !important;
      right: 0 !important;
      background-color: #161622 !important;
      border: 1px solid #2e2e3e !important;
      border-radius: 8px !important;
      min-width: 240px !important;
      box-shadow: 0 10px 25px rgba(0, 0, 0, 0.5) !important;
      overflow: hidden !important;
      animation: ffmpegFadeIn 0.15s ease-out !important;
      padding: 6px 0 !important;
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
    dlBtn.textContent = 'Envoyer';
    dlBtn.title = 'Envoyer à la file de téléchargement';
    dlBtn.addEventListener('click', () => {
      chrome.runtime.sendMessage({
        action: 'sendToApp',
        url: stream.url,
        title: stream.title || '',
        play: false
      }, (response) => {
        if (response && response.status === 'ok') {
          showToast('✓ Envoyé avec succès à FFmpeg Studio !');
        } else {
          showToast('⚠ Erreur. Vérifiez que FFmpeg Studio est ouvert.', false);
        }
      });
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
  const panels = document.querySelectorAll('.ffmpeg-dropdown-menu');
  panels.forEach(updateDropdown);
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

// Find HTML5 video players on the page and append the button to their parent wrapper
function scanForVideos() {
  injectStyles();
  const videos = document.getElementsByTagName('video');
  for (let i = 0; i < videos.length; i++) {
    const video = videos[i];
    
    // Ignore small layout videos (like badges or tiny avatars)
    if (video.offsetWidth < 150 || video.offsetHeight < 100) continue;
    
    const parent = video.parentElement;
    if (!parent) continue;
    
    // Skip if already attached
    if (parent.querySelector('.ffmpeg-float-panel')) continue;
    
    // Style parent to relative if static so absolute children align correctly
    const style = window.getComputedStyle(parent);
    if (style.position === 'static') {
      parent.style.position = 'relative';
    }
    
    const panel = createFloatPanel(video);
    parent.appendChild(panel);
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
