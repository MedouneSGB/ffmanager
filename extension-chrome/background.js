// Service Worker de l'extension
let detectedStreams = {};
let hlsMappings = {}; // Association URL propre de variante -> Résolution (ex: "1080")
let extensionEnabled = true;

// Charge l'état initial
chrome.storage.local.get({ enabled: true }, (result) => {
  extensionEnabled = result.enabled;
});

// Écoute les changements pour actualiser dynamiquement l'état
chrome.storage.onChanged.addListener((changes, area) => {
  if (area === "local" && changes.enabled) {
    extensionEnabled = changes.enabled.newValue;
    if (!extensionEnabled) {
      detectedStreams = {};
      chrome.action.setBadgeText({ text: "" });
    }
  }
});

function getCleanUrl(url) {
  try {
    const urlObj = new URL(url);
    return urlObj.origin + urlObj.pathname;
  } catch (e) {
    return url.split('?')[0].split('#')[0];
  }
}

function getDeduplicationKey(url) {
  try {
    const urlObj = new URL(url);
    const pathname = urlObj.pathname.toLowerCase();
    if (pathname.endsWith('.m3u8') || pathname.endsWith('.mp4') || pathname.endsWith('.mpd') || pathname.endsWith('.webm') ||
        pathname.includes('.m3u8/') || pathname.includes('.mp4/') || pathname.includes('.mpd/') || pathname.includes('.webm/')) {
      return urlObj.origin + urlObj.pathname;
    }
  } catch (e) {
    // Fallback
  }
  return url;
}

function guessResolutionFromUrl(url) {
  const cleanUrl = getCleanUrl(url);
  
  // Chercher d'abord un motif comme "1080p", "720p", "480p", "360p", "2160p", "1440p"
  const pMatch = cleanUrl.match(/(\d{3,4})[pP]/);
  if (pMatch) {
    const res = pMatch[1];
    if (["2160", "1440", "1080", "720", "480", "360", "240"].includes(res)) {
      return res;
    }
  }
  
  // Chercher des nombres autonomes de résolutions courantes
  const commonResolutions = ["2160", "1440", "1080", "720", "480", "360", "240"];
  for (const res of commonResolutions) {
    const regex = new RegExp(`[^0-9a-zA-Z]${res}[^0-9a-zA-Z]|^${res}[^0-9a-zA-Z]|[^0-9a-zA-Z]${res}$`);
    if (regex.test(cleanUrl)) {
      return res;
    }
  }
  return null;
}

async function checkAndParseMasterPlaylist(masterUrl, tabId) {
  try {
    const response = await fetch(masterUrl);
    const text = await response.text();
    
    if (text.includes("#EXT-X-STREAM-INF")) {
      // C'est un master playlist adaptatif
      updateStreamQuality(tabId, masterUrl, "Flux HLS - Multi (Adaptatif)");
      
      const lines = text.split("\n");
      let currentResolution = null;
      
      for (let i = 0; i < lines.length; i++) {
        const line = lines[i].trim();
        if (line.startsWith("#EXT-X-STREAM-INF:")) {
          const resMatch = line.match(/RESOLUTION=(\d+)x(\d+)/);
          if (resMatch) {
            currentResolution = resMatch[2]; // ex: "1080"
          }
        } else if (line && !line.startsWith("#") && currentResolution) {
          try {
            let variantUrl = new URL(line, masterUrl).toString();
            const cleanVariant = getCleanUrl(variantUrl);
            hlsMappings[cleanVariant] = currentResolution;
            
            // Si la variante avait déjà été interceptée, mettre à jour son label
            updateStreamQuality(tabId, variantUrl, `Flux HLS - ${currentResolution}`);
          } catch (e) {
            // Ignorer les erreurs d'URL invalides
          }
          currentResolution = null;
        }
      }
    }
  } catch (e) {
    console.error("Erreur de lecture HLS :", e);
  }
}

function updateStreamQuality(tabId, url, quality) {
  if (detectedStreams[tabId]) {
    const cleanTargetUrl = getCleanUrl(url);
    const stream = detectedStreams[tabId].find(s => getCleanUrl(s.url) === cleanTargetUrl);
    if (stream) {
      stream.quality = quality;
      // Notifier la popup si elle est ouverte pour mise à jour dynamique
      chrome.runtime.sendMessage({
        action: "streamUpdated",
        url: stream.url,
        quality: quality
      }).catch(() => {});
      
      // Notifier le content script de l'onglet
      chrome.tabs.sendMessage(tabId, {
        action: "streamUpdated",
        url: stream.url,
        quality: quality
      }).catch(() => {});
    }
  }
}

function notifyPopupOfStreams(tabId) {
  chrome.runtime.sendMessage({
    action: "streamsDetected",
    tabId: tabId,
    streams: detectedStreams[tabId] || []
  }).catch(() => {});
}

function addStream(tabId, url) {
  if (!detectedStreams[tabId]) {
    detectedStreams[tabId] = [];
  }
  
  const dedupKey = getDeduplicationKey(url);
  const existingIndex = detectedStreams[tabId].findIndex(s => getDeduplicationKey(s.url) === dedupKey);
  
  if (existingIndex !== -1) {
    detectedStreams[tabId][existingIndex].url = url;
    chrome.tabs.sendMessage(tabId, {
      action: "streamsDetected",
      streams: detectedStreams[tabId]
    }).catch(() => {});
    notifyPopupOfStreams(tabId);
    return;
  }
  
  let quality = "Auto/Direct";
  const cleanUrl = getCleanUrl(url);
  
  if (hlsMappings[cleanUrl]) {
    quality = `Flux HLS - ${hlsMappings[cleanUrl]}`;
  } else if (url.includes(".m3u8")) {
    const guessedRes = guessResolutionFromUrl(url);
    if (guessedRes) {
      quality = `Flux HLS - ${guessedRes}`;
    } else {
      quality = "Flux HLS";
    }
  } else if (url.includes(".mpd")) {
    const guessedRes = guessResolutionFromUrl(url);
    if (guessedRes) {
      quality = `Flux DASH - ${guessedRes}`;
    } else {
      quality = "Flux DASH";
    }
  } else {
    const guessedRes = guessResolutionFromUrl(url);
    if (guessedRes) {
      quality = `${guessedRes}p`;
    } else if (url.endsWith(".mp4")) {
      quality = "Direct MP4";
    } else if (url.endsWith(".webm")) {
      quality = "Direct WebM";
    }
  }
  
  chrome.tabs.get(tabId, (tab) => {
    let title = "";
    if (!chrome.runtime.lastError && tab && tab.title) {
      title = tab.title;
    }
    
    if (!detectedStreams[tabId].some(s => getDeduplicationKey(s.url) === dedupKey)) {
      detectedStreams[tabId].push({ url, quality, title });
      
      chrome.action.setBadgeText({
        tabId: tabId,
        text: detectedStreams[tabId].length.toString()
      });
      chrome.action.setBadgeBackgroundColor({
        tabId: tabId,
        color: "#7c4dff"
      });
      
      chrome.tabs.sendMessage(tabId, {
        action: "streamsDetected",
        streams: detectedStreams[tabId]
      }).catch(() => {});
      notifyPopupOfStreams(tabId);
    }
  });
}

chrome.webRequest.onBeforeRequest.addListener(
  (details) => {
    if (!extensionEnabled) return;
    const url = details.url;
    // Ignorer les segments d'init ou de flux fragmentés (ex: Twitter segments)
    if (url.includes("/avc1/") || url.includes("/mp4a/") || url.includes(".m4s") || url.includes("seg_") || url.includes("-init.mp4")) {
      return;
    }
    // Filtrage des flux vidéo ou fichiers multimédias (.m3u8, .mp4, .mpd, .webm)
    if (url.includes(".m3u8") || url.includes(".mp4") || url.includes(".mpd") || url.includes(".webm")) {
      const tabId = details.tabId;
      if (tabId < 0) return;
      
      // Si c'est du HLS, lancer l'analyse en arrière-plan
      if (url.includes(".m3u8") && !hlsMappings[getCleanUrl(url)]) {
        checkAndParseMasterPlaylist(url, tabId);
      }
      
      addStream(tabId, url);
    }
  },
  { urls: ["<all_urls>"] }
);

// Effacer les flux détectés au rechargement/changement de page sur l'onglet
chrome.tabs.onUpdated.addListener((tabId, changeInfo, tab) => {
  if (changeInfo.status === 'loading') {
    delete detectedStreams[tabId];
    chrome.action.setBadgeText({ tabId, text: "" });
    notifyPopupOfStreams(tabId);
  }
});

chrome.tabs.onRemoved.addListener((tabId) => {
  delete detectedStreams[tabId];
});

// Communication avec la popup
chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message.action === "getStreams") {
    const tabId = (sender && sender.tab) ? sender.tab.id : null;
    if (tabId !== null) {
      sendResponse({ streams: detectedStreams[tabId] || [] });
    } else {
      chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
        if (tabs.length > 0) {
          const activeTabId = tabs[0].id;
          sendResponse({ streams: detectedStreams[activeTabId] || [] });
        } else {
          sendResponse({ streams: [] });
        }
      });
      return true; // Garder la communication ouverte pour la réponse asynchrone
    }
  } else if (message.action === "sendToApp") {
    fetch("http://localhost:8555/add-stream", {
      method: "POST",
      headers: {
        "Content-Type": "application/json"
      },
      body: JSON.stringify({
        url: message.url,
        title: message.title,
        play: message.play,
        download: message.download || false,
        outputPath: message.outputPath || null
      })
    })
    .then(response => response.json())
    .then(data => {
      sendResponse(data);
    })
    .catch(error => {
      sendResponse({ status: "error", error: error.message });
    });
    return true; // Garder la communication ouverte pour la réponse asynchrone
  } else if (message.action === "getDefaultPath") {
    const cleanTitle = encodeURIComponent(message.title || "video");
    const preset = message.preset || "mp4";
    fetch(`http://localhost:8555/get-default-path?title=${cleanTitle}&preset=${preset}`)
      .then(response => response.json())
      .then(data => {
        sendResponse(data);
      })
      .catch(error => {
        sendResponse({ status: "error", error: error.message });
      });
    return true; // Garder la communication ouverte
  } else if (message.action === "pickDirectory") {
    fetch("http://localhost:8555/pick-directory", { method: "POST" })
      .then(response => response.json())
      .then(data => {
        sendResponse(data);
      })
      .catch(error => {
        sendResponse({ status: "error", error: error.message });
      });
    return true; // Garder la communication ouverte
  }
});
