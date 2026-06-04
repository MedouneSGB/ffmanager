// Service Worker de l'extension
let detectedStreams = {};
let hlsMappings = {}; // Association URL propre de variante -> Résolution (ex: "1080")

function getCleanUrl(url) {
  try {
    const urlObj = new URL(url);
    return urlObj.origin + urlObj.pathname;
  } catch (e) {
    return url.split('?')[0].split('#')[0];
  }
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
    }
  }
}

function addStream(tabId, url) {
  if (!detectedStreams[tabId]) {
    detectedStreams[tabId] = [];
  }
  
  if (!detectedStreams[tabId].some(s => s.url === url)) {
    let quality = "Auto/Direct";
    const cleanUrl = getCleanUrl(url);
    
    if (hlsMappings[cleanUrl]) {
      quality = `Flux HLS - ${hlsMappings[cleanUrl]}`;
    } else if (url.includes(".m3u8")) {
      const guessedRes = guessResolutionFromUrl(url);
      if (guessedRes) {
        quality = `Flux HLS - ${guessedRes}`;
      } else {
        quality = "Flux HLS"; // Valeur neutre par défaut si non détectée
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
    
    // Récupérer le titre de l'onglet de manière asynchrone
    chrome.tabs.get(tabId, (tab) => {
      let title = "";
      if (!chrome.runtime.lastError && tab && tab.title) {
        title = tab.title;
      }
      
      // S'assurer qu'un autre écouteur n'a pas ajouté le flux entre temps
      if (!detectedStreams[tabId].some(s => s.url === url)) {
        detectedStreams[tabId].push({ url, quality, title });
        
        // Mettre à jour le badge de l'icône de l'extension (nombre de flux)
        chrome.action.setBadgeText({
          tabId: tabId,
          text: detectedStreams[tabId].length.toString()
        });
        chrome.action.setBadgeBackgroundColor({
          tabId: tabId,
          color: "#7c4dff" // Couleur d'accent violet
        });
      }
    });
  }
}

chrome.webRequest.onBeforeRequest.addListener(
  (details) => {
    const url = details.url;
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
  }
});

chrome.tabs.onRemoved.addListener((tabId) => {
  delete detectedStreams[tabId];
});

// Communication avec la popup
chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (message.action === "getStreams") {
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
});
