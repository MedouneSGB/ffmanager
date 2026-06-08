document.addEventListener("DOMContentLoaded", () => {
  const streamList = document.getElementById("streamList");
  const statusMsg = document.getElementById("statusMsg");

  const toggle = document.getElementById("toggleExtension");
  const statusLabel = document.getElementById("toggleStatusLabel");
  const sponsorBtn = document.getElementById("sponsorBtn");

  // Rediriger vers GitHub Sponsors
  sponsorBtn.addEventListener("click", () => {
    chrome.tabs.create({ url: "https://github.com/sponsors/MedouneSGB" });
  });

  const refreshPageBtnInit = document.getElementById("refreshPageBtn");
  if (refreshPageBtnInit) {
    refreshPageBtnInit.addEventListener("click", () => {
      chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
        if (tabs[0] && tabs[0].id) {
          chrome.tabs.reload(tabs[0].id);
        }
      });
    });
  }

  function renderStreams(streams) {
    if (!toggle.checked) {
      streamList.innerHTML = `<div class="empty-state" style="color: #7a7a8a;">L'extension est désactivée.</div>`;
      return;
    }
    
    if (streams && streams.length > 0) {
      streamList.innerHTML = "";
      streams.forEach((stream) => {
        const item = document.createElement("div");
        item.className = "stream-item";
        
        const header = document.createElement("div");
        header.className = "stream-header";
        
        const badge = document.createElement("span");
        badge.className = "badge";
        badge.textContent = stream.quality;
        
        const btn = document.createElement("button");
        btn.className = "btn";
        btn.textContent = "Télécharger";
        btn.addEventListener("click", () => showDownloadPanel(stream.url, stream.title || ""));
        
        const btnPlay = document.createElement("button");
        btnPlay.className = "btn btn-play";
        btnPlay.textContent = "Lire";
        btnPlay.addEventListener("click", () => sendPlayToApp(stream.url, stream.title || ""));
        
        const btnGroup = document.createElement("div");
        btnGroup.style.display = "flex";
        btnGroup.style.gap = "6px";
        btnGroup.appendChild(btn);
        btnGroup.appendChild(btnPlay);
        
        header.appendChild(badge);
        header.appendChild(btnGroup);
        
        const urlDiv = document.createElement("div");
        urlDiv.className = "stream-url";
        urlDiv.textContent = stream.url;
        urlDiv.title = stream.url; // Afficher l'URL entière au survol
        
        item.appendChild(header);
        item.appendChild(urlDiv);
        streamList.appendChild(item);
      });
    } else {
      streamList.innerHTML = `
        <div class="empty-state">
          Aucun flux détecté sur cette page.<br>Lancez une vidéo pour capturer le lien.
          <button id="refreshPageBtn" class="btn" style="margin-top: 14px; background-color: rgba(124, 77, 255, 0.1); border: 1px solid #7c4dff; color: #9e7dff; padding: 8px 16px; font-size: 11px; width: 100%; border-radius: 6px; display: inline-flex; align-items: center; justify-content: center; gap: 6px; transition: background-color 0.2s; cursor: pointer;">🔄 Rafraîchir la page</button>
        </div>
      `;
      const refreshBtn = document.getElementById("refreshPageBtn");
      if (refreshBtn) {
        refreshBtn.addEventListener("click", () => {
          chrome.tabs.query({ active: true, currentWindow: true }, (tabs) => {
            if (tabs[0] && tabs[0].id) {
              chrome.tabs.reload(tabs[0].id);
            }
          });
        });
        refreshBtn.addEventListener("mouseenter", () => {
          refreshBtn.style.backgroundColor = "rgba(124, 77, 255, 0.2)";
        });
        refreshBtn.addEventListener("mouseleave", () => {
          refreshBtn.style.backgroundColor = "rgba(124, 77, 255, 0.1)";
        });
      }
    }
  }

  // Récupérer les flux détectés depuis background.js après avoir chargé l'état activé
  chrome.storage.local.get({ enabled: true }, (result) => {
    toggle.checked = result.enabled;
    statusLabel.textContent = result.enabled ? "Extension active" : "Extension désactivée";
    
    chrome.runtime.sendMessage({ action: "getStreams" }, (response) => {
      const streams = response ? response.streams : [];
      renderStreams(streams);
    });
  });

  // Écouter le changement d'état du switch
  toggle.addEventListener("change", () => {
    const isEnabled = toggle.checked;
    chrome.storage.local.set({ enabled: isEnabled }, () => {
      statusLabel.textContent = isEnabled ? "Extension active" : "Extension désactivée";
      if (!isEnabled) {
        renderStreams([]);
      } else {
        chrome.runtime.sendMessage({ action: "getStreams" }, (response) => {
          const streams = response ? response.streams : [];
          renderStreams(streams);
        });
      }
    });
  });

  // Écouter les mises à jour de flux en temps réel
  chrome.runtime.onMessage.addListener((message) => {
    if (message.action === "streamsDetected") {
      chrome.runtime.sendMessage({ action: "getStreams" }, (response) => {
        const streams = response ? response.streams : [];
        renderStreams(streams);
      });
    } else if (message.action === "streamUpdated") {
      const items = streamList.querySelectorAll(".stream-item");
      items.forEach((item) => {
        const urlDiv = item.querySelector(".stream-url");
        if (urlDiv && urlDiv.textContent === message.url) {
          const badge = item.querySelector(".badge");
          if (badge) {
            badge.textContent = message.quality;
          }
        }
      });
    }
  });

  // Gérer l'affichage du panel de téléchargement IDM
  function showDownloadPanel(url, title) {
    streamList.style.display = "none";
    document.getElementById("downloadPanel").style.display = "block";
    
    document.getElementById("panelUrl").value = url;
    document.getElementById("panelFileName").value = "";
    document.getElementById("panelFileName").placeholder = "Chargement du nom...";
    document.getElementById("panelDestFolder").value = "";
    document.getElementById("panelDestFolder").placeholder = "Chargement du dossier...";
    document.getElementById("panelFormat").value = "mp4";
    
    // Récupérer le chemin de destination par défaut depuis le serveur Java
    chrome.runtime.sendMessage({ action: "getDefaultPath", title: title, preset: "mp4" }, (data) => {
      if (data && data.status !== "error") {
        document.getElementById("panelFileName").value = data.fileName;
        document.getElementById("panelDestFolder").value = data.defaultFolder;
      } else {
        // Fallback si l'application locale n'est pas ouverte
        const safeTitle = (title || "video").replace(/[\\/:*?"<>|]/g, "_").trim();
        document.getElementById("panelFileName").value = (safeTitle ? safeTitle : "video") + ".mp4";
        document.getElementById("panelDestFolder").value = "C:\\Users\\...\\Downloads";
        showStatus("⚠ Lancez FFmpeg Studio pour charger le dossier par défaut.", "#ff9800", "rgba(255, 152, 0, 0.1)");
      }
    });
  }

  function hideDownloadPanel() {
    document.getElementById("downloadPanel").style.display = "none";
    streamList.style.display = "block";
  }

  // Fermer le panel
  document.getElementById("closePanelBtn").addEventListener("click", hideDownloadPanel);
  document.getElementById("cancelDownloadBtn").addEventListener("click", hideDownloadPanel);

  // Parcourir le dossier de destination local via l'application Java
  document.getElementById("browseDestBtn").addEventListener("click", (e) => {
    e.preventDefault();
    const btn = document.getElementById("browseDestBtn");
    btn.disabled = true;
    btn.textContent = "Choix...";
    
    chrome.runtime.sendMessage({ action: "pickDirectory" }, (data) => {
      btn.disabled = false;
      btn.textContent = "Parcourir...";
      if (data && data.status === "ok" && data.path) {
        document.getElementById("panelDestFolder").value = data.path;
      } else {
        alert("Impossible d'ouvrir le sélecteur. Assurez-vous que FFmpeg Studio est lancé.");
      }
    });
  });

  // Mettre à jour l'extension du fichier en fonction du format sélectionné
  document.getElementById("panelFormat").addEventListener("change", (e) => {
    const format = e.target.value;
    const fileNameInput = document.getElementById("panelFileName");
    let currentName = fileNameInput.value;
    
    if (format === "mp3") {
      if (currentName.endsWith(".mp4")) {
        fileNameInput.value = currentName.substring(0, currentName.length - 4) + ".mp3";
      } else if (!currentName.endsWith(".mp3")) {
        fileNameInput.value = currentName + ".mp3";
      }
    } else { // mp4
      if (currentName.endsWith(".mp3")) {
        fileNameInput.value = currentName.substring(0, currentName.length - 4) + ".mp4";
      } else if (!currentName.endsWith(".mp4")) {
        fileNameInput.value = currentName + ".mp4";
      }
    }
  });

  // Confirmer le téléchargement
  document.getElementById("startDownloadBtn").addEventListener("click", () => {
    const url = document.getElementById("panelUrl").value;
    const fileName = document.getElementById("panelFileName").value.trim();
    const destFolder = document.getElementById("panelDestFolder").value.trim();
    
    if (!fileName || fileName === "Chargement...") {
      alert("Veuillez saisir un nom de fichier valide.");
      return;
    }
    if (!destFolder || destFolder === "Chargement...") {
      alert("Veuillez saisir un dossier de destination valide.");
      return;
    }

    // Reconstruction du chemin complet
    let sep = "\\";
    if (destFolder.includes("/")) {
      sep = "/";
    }
    const outputPath = destFolder + (destFolder.endsWith(sep) ? "" : sep) + fileName;

    // Nom sans extension pour le titre du Job
    let title = fileName;
    if (title.endsWith(".mp4") || title.endsWith(".mp3")) {
      title = title.substring(0, title.length - 4);
    }

    chrome.runtime.sendMessage({
      action: "sendToApp",
      url: url,
      title: title,
      play: false,
      download: true,
      outputPath: outputPath
    }, (data) => {
      if (data && data.status === "ok") {
        showStatus("✓ Téléchargement démarré dans FFmpeg Studio !", "#00e676", "rgba(0, 230, 118, 0.1)");
        hideDownloadPanel();
      } else {
        showStatus("⚠ Assurez-vous que FFmpeg Studio est ouvert.", "#ff1744", "rgba(255, 23, 68, 0.1)");
      }
    });
  });

  // Envoyer l'URL du flux à l'application Java pour la lecture directe
  function sendPlayToApp(url, title) {
    chrome.runtime.sendMessage({
      action: "sendToApp",
      url: url,
      title: title,
      play: true
    }, (data) => {
      if (data && data.status === "ok") {
        showStatus("✓ Lecture lancée avec succès !", "#00e676", "rgba(0, 230, 118, 0.1)");
      } else {
        showStatus("⚠ Assurez-vous que FFmpeg Studio est ouvert.", "#ff1744", "rgba(255, 23, 68, 0.1)");
      }
    });
  }

  function showStatus(msg, color, bgColor) {
    statusMsg.style.display = "block";
    statusMsg.style.color = color;
    statusMsg.style.backgroundColor = bgColor;
    statusMsg.textContent = msg;
    setTimeout(() => {
      statusMsg.style.display = "none";
    }, 2500);
  }
});
