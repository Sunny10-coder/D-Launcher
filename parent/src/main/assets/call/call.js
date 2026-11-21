/*
 * My Little World - bundled WebRTC client.
 *
 * Runs inside an Android WebView loaded from a secure app-assets origin. It talks
 * only to the family service signalling socket, never to third-party servers.
 * All control comes from the native layer via window.mlwStart / mlwSetMuted /
 * mlwSetVideo / mlwHangup, and all state is reported back through the
 * AndroidCallBridge JavaScript interface.
 */
(function () {
  "use strict";

  var ws = null;
  var pc = null;
  var localStream = null;
  var pendingCandidates = [];
  var remoteDescriptionSet = false;
  var role = "child";
  var media = "audio";
  var videoEnabled = false;
  var muted = true;
  var closed = false;
  var reconnectTimer = null;
  var reconnectAttempts = 0;
  var maxReconnects = 4;

  var remoteEl = document.getElementById("remote");
  var localEl = document.getElementById("local");
  var orb = document.getElementById("orb");

  function bridge(event) {
    try {
      if (window.AndroidCallBridge && window.AndroidCallBridge.post) {
        window.AndroidCallBridge.post(JSON.stringify(event));
      }
    } catch (e) {
      /* bridge unavailable: ignore */
    }
  }

  function state(value) {
    bridge({ type: "state", value: value });
  }

  function fail(message) {
    bridge({ type: "error", message: String(message || "call error") });
  }

  function send(obj) {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify(obj));
      return true;
    }
    return false;
  }

  function isPolite() {
    return role === "child";
  }

  function buildPc() {
    var config = { iceServers: [] };
    var servers = (window.__mlwIceServers || []).map(function (s) {
      var entry = { urls: s.urls };
      if (s.username) entry.username = s.username;
      if (s.credential) entry.credential = s.credential;
      return entry;
    });
    if (servers.length === 0) {
      // Development default. Production deployments supply a TURN server so calls
      // work behind strict carrier NATs.
      config.iceServers = [{ urls: "stun:stun.l.google.com:19302" }];
    } else {
      config.iceServers = servers;
    }
    var peer = new RTCPeerConnection(config);

    peer.onicecandidate = function (event) {
      if (event.candidate) send({ type: "ice", candidate: event.candidate });
    };
    peer.ontrack = function (event) {
      if (event.streams && event.streams[0]) {
        remoteEl.srcObject = event.streams[0];
      } else if (event.track) {
        var stream = new MediaStream([event.track]);
        remoteEl.srcObject = stream;
      }
      state("media-flowing");
    };
    peer.onconnectionstatechange = function () {
      var s = peer.connectionState;
      if (s === "connected") {
        reconnectAttempts = 0;
        orb.classList.add("talking");
        state("connected");
      } else if (s === "disconnected") {
        state("reconnecting");
      } else if (s === "failed") {
        state("reconnecting");
        scheduleReconnect();
      } else if (s === "closed") {
        state("ended");
      }
    };
    return peer;
  }

  async function getLocalStream(withVideo) {
    if (localStream) {
      var hasVideo = localStream.getVideoTracks().length > 0;
      if (hasVideo === withVideo) return localStream;
      localStream.getTracks().forEach(function (t) { t.stop(); });
    }
    var constraints = { audio: { echoCancellation: true, noiseSuppression: true }, video: false };
    if (withVideo) {
      constraints.video = { facingMode: "user", width: { ideal: 640 }, height: { ideal: 480 } };
    }
    localStream = await navigator.mediaDevices.getUserMedia(constraints);
    localStream.getAudioTracks().forEach(function (track) {
      track.enabled = !muted;
    });
    localEl.srcObject = localStream;
    localEl.classList.toggle("on", withVideo);
    bridge({
      type: "media",
      audio: localStream.getAudioTracks().length > 0,
      video: localStream.getVideoTracks().length > 0
    });
    return localStream;
  }

  async function attachTracks(peer, stream) {
    stream.getTracks().forEach(function (track) {
      var existing = peer.getSenders().find(function (s) { return s.track && s.track.kind === track.kind; });
      if (existing) {
        existing.replaceTrack(track);
      } else {
        peer.addTrack(track, stream);
      }
    });
  }

  async function createOffer() {
    if (!pc) return;
    try {
      var offer = await pc.createOffer();
      await pc.setLocalDescription(offer);
      send({ type: "offer", sdp: offer.sdp });
      state("connecting");
    } catch (e) {
      fail("Could not start the call");
    }
  }

  async function handleOffer(sdp) {
    if (!pc) return;
    try {
      await pc.setRemoteDescription({ type: "offer", sdp: sdp });
      remoteDescriptionSet = true;
      await flushCandidates();
      var answer = await pc.createAnswer();
      await pc.setLocalDescription(answer);
      send({ type: "answer", sdp: answer.sdp });
      state("connecting");
    } catch (e) {
      fail("Could not answer the call");
    }
  }

  async function handleAnswer(sdp) {
    if (!pc || remoteDescriptionSet) return;
    try {
      await pc.setRemoteDescription({ type: "answer", sdp: sdp });
      remoteDescriptionSet = true;
      await flushCandidates();
    } catch (e) {
      fail("Could not complete the call");
    }
  }

  async function flushCandidates() {
    var queue = pendingCandidates.slice();
    pendingCandidates = [];
    for (var i = 0; i < queue.length; i++) {
      try { await pc.addIceCandidate(queue[i]); } catch (e) { /* ignore stale candidate */ }
    }
  }

  function scheduleReconnect() {
    if (closed) return;
    if (reconnectAttempts >= maxReconnects) {
      state("failed");
      return;
    }
    reconnectAttempts += 1;
    if (reconnectTimer) clearTimeout(reconnectTimer);
    reconnectTimer = setTimeout(function () {
      if (closed) return;
      state("reconnecting");
      connectSocket();
      if (isPolite() === false && pc) {
        // The polite peer re-offers after reconnecting, keeping negotiation simple.
        createOffer();
      }
    }, 1500 * reconnectAttempts);
  }

  function connectSocket() {
    if (!window.__mlwConfig) return;
    if (ws) {
      try { ws.close(); } catch (e) { /* ignore */ }
      ws = null;
    }
    try {
      ws = new WebSocket(window.__mlwConfig.websocketUrl);
    } catch (e) {
      fail("Could not reach the family service");
      return;
    }
    ws.onopen = function () {
      send({ type: "hello", role: role, media: media });
    };
    ws.onmessage = function (event) {
      var message;
      try { message = JSON.parse(event.data); } catch (e) { return; }
      handleSignal(message);
    };
    ws.onclose = function () {
      if (!closed) {
        state("reconnecting");
        scheduleReconnect();
      }
    };
    ws.onerror = function () {
      state("reconnecting");
    };
  }

  function handleSignal(message) {
    switch (message.type) {
      case "welcome":
        if ((message.peers || 0) > 0) {
          createOffer();
        }
        break;
      case "peer-joined":
        if (isPolite()) {
          createOffer();
        }
        break;
      case "peer-left":
        state("reconnecting");
        break;
      case "offer":
        handleOffer(message.sdp);
        break;
      case "answer":
        handleAnswer(message.sdp);
        break;
      case "ice":
        if (pc && remoteDescriptionSet) {
          pc.addIceCandidate(message.candidate).catch(function () {});
        } else {
          pendingCandidates.push(message.candidate);
        }
        break;
      case "bye":
        finish("remote-ended");
        break;
      case "rejected":
        state("rejected");
        finish("rejected");
        break;
      case "error":
        fail(message.message || "Call error");
        break;
      default:
        break;
    }
  }

  function finish(reason) {
    if (closed) return;
    closed = true;
    if (reconnectTimer) clearTimeout(reconnectTimer);
    try { if (ws) ws.close(); } catch (e) { /* ignore */ }
    teardownMedia();
    state("ended");
    bridge({ type: "closed", reason: reason });
  }

  function teardownMedia() {
    try {
      if (localStream) localStream.getTracks().forEach(function (t) { t.stop(); });
    } catch (e) { /* ignore */ }
    localStream = null;
    try {
      if (pc) {
        pc.getSenders && pc.getSenders().forEach(function (s) { if (s.track) s.track.stop(); });
        pc.close();
      }
    } catch (e) { /* ignore */ }
    pc = null;
    remoteEl.srcObject = null;
    localEl.srcObject = null;
    localEl.classList.remove("on");
    orb.classList.remove("talking");
  }

  /* ---- Native API ---- */

  window.mlwStart = async function (configJson) {
    var config = JSON.parse(configJson);
    window.__mlwConfig = config;
    window.__mlwIceServers = config.iceServers || [];
    role = config.role || "child";
    media = config.media || "audio";
    muted = config.startsMuted !== false;

    if (config.mode === "simulated") {
      // Explicit local development mode: no media server is configured.
      state("simulated");
      bridge({ type: "closed", reason: "simulated" });
      return;
    }

    try {
      await getLocalStream(media === "video");
      pc = buildPc();
      await attachTracks(pc, localStream);
      state("connecting");
      connectSocket();
    } catch (e) {
      fail(e && e.name === "NotAllowedError" ? "permission" : "media");
    }
  };

  window.mlwSetMuted = function (value) {
    muted = !!value;
    if (localStream) {
      localStream.getAudioTracks().forEach(function (t) { t.enabled = !muted; });
    }
    return muted;
  };

  window.mlwSetVideo = async function (enabled) {
    videoEnabled = !!enabled;
    if (!pc) return videoEnabled;
    try {
      var stream = await getLocalStream(videoEnabled);
      await attachTracks(pc, stream);
      return videoEnabled;
    } catch (e) {
      fail("camera");
      return false;
    }
  };

  window.mlwHangup = function () {
    try { send({ type: "bye" }); } catch (e) { /* ignore */ }
    finish("local-ended");
  };

  window.addEventListener("pagehide", teardownMedia);
  window.addEventListener("beforeunload", teardownMedia);
})();
