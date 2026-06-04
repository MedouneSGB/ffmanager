document.addEventListener("DOMContentLoaded", () => {
  const streamList = document.getElementById("streamList");
  const statusMsg = document.getElementById("statusMsg");

  // Récupérer les flux détectés depuis background.js
  chrome.runtime.sendMessage({ action: "getStreams" }, (response) => {
    const streams = response ? response.streams : [];
    
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
        btn.textContent = "Envoyer";
        btn.addEventListener("click", () => sendToApp(stream.url, false));
        
        const btnPlay = document.createElement("button");
        btnPlay.className = "btn btn-play";
        btnPlay.textContent = "Lire";
        btnPlay.addEventListener("click", () => sendToApp(stream.url, true));
        
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
    }
  });

  // Envoyer l'URL du flux à l'application Java via le serveur HTTP local
  function sendToApp(url, play) {
    fetch("http://localhost:8555/add-stream", {
      method: "POST",
      headers: {
        "Content-Type": "application/json"
      },
      body: JSON.stringify({ url: url, play: play })
    })
    .then(response => response.json())
    .then(data => {
      if (data.status === "ok") {
        const actionMsg = play ? "Lancé" : "Envoyé";
        showStatus("✓ " + actionMsg + " avec succès dans FFmpeg Studio !", "#00e676", "rgba(0, 230, 118, 0.1)");
      } else {
        showStatus("⚠ Erreur de réponse de l'application.", "#ff1744", "rgba(255, 23, 68, 0.1)");
      }
    })
    .catch(error => {
      showStatus("⚠ Assurez-vous que FFmpeg Studio est ouvert.", "#ff1744", "rgba(255, 23, 68, 0.1)");
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
