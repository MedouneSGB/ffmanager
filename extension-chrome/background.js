// Service Worker de l'extension
let detectedStreams = {};

chrome.webRequest.onBeforeRequest.addListener(
  (details) => {
    const url = details.url;
    // Filtrage des flux vidéo ou fichiers multimédias (.m3u8, .mp4, .mpd, .webm)
    if (url.includes(".m3u8") || url.includes(".mp4") || url.includes(".mpd") || url.includes(".webm")) {
      const tabId = details.tabId;
      if (tabId < 0) return;
      
      if (!detectedStreams[tabId]) {
        detectedStreams[tabId] = [];
      }
      
      // Éviter les doublons exacts
      if (!detectedStreams[tabId].some(s => s.url === url)) {
        // Deviner la qualité ou le format
        let quality = "Auto/Direct";
        if (url.includes("1080")) quality = "1080p";
        else if (url.includes("720")) quality = "720p";
        else if (url.includes("480")) quality = "480p";
        else if (url.includes("360")) quality = "360p";
        else if (url.endsWith(".mp4")) quality = "Direct MP4";
        else if (url.includes(".m3u8")) quality = "Flux HLS";
        
        detectedStreams[tabId].push({ url, quality });
        
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
