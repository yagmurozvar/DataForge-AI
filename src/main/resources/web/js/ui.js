import { ICONS, SHADOW_PADDING, TOAST_DURATION } from './constants.js';
import { elements, state, t } from './state.js';
import { bridge } from './bridge.js';
import { escapeHtml } from './utils.js';

var searchTimer = null;
var notesTimer = null;
var toastTimer = null;
var measureQueued = false;

function byId(id) {
    return document.getElementById(id);
}

export function cacheElements() {
    [
        "root", "bar", "grip", "search-toggle", "search-input", "search-clear",
        "add-toggle", "assistant-toggle", "settings-toggle", "results", "add-menu",
        "settings-menu", "assistant", "assistant-log", "assistant-text", "assistant-send",
        "editor", "editor-title", "editor-code", "editor-category", "editor-title-input",
        "editor-description", "editor-specs", "editor-safety", "toast", "sheet",
        "sheet-title", "sheet-body", "sheet-notes", "sheet-notes-input",
        "theme-switch", "pin-switch",
        "editor-image-file", "editor-image-preview", "editor-image-base64", "editor-image-clear",
        "retry-orphans"
    ].forEach(function (id) {
        elements[id] = byId(id);
    });

    injectAssistantUploadUI();
    injectTrashUI();
}

function icon(name) {
    return '<svg class="icon" viewBox="0 0 24 24" aria-hidden="true">' + (ICONS[name] || "") + "</svg>";
}

export function paintIcons() {
    elements.grip.innerHTML = icon("grip");
    elements["search-toggle"].innerHTML = icon("search");
    elements["search-clear"].innerHTML = icon("close");
    elements["add-toggle"].innerHTML = icon("plus");
    elements["assistant-toggle"].innerHTML = icon("bot");
    elements["settings-toggle"].innerHTML = icon("more");
    elements["assistant-send"].innerHTML = icon("send");

    document.querySelectorAll("[data-action]").forEach(function (node) {
        if (node.tagName !== "BUTTON" || node.innerHTML.trim() !== "") {
            return;
        }
        var mapping = {
            "open-original": "file_open",
            "close-assistant": "close",
            "close-editor": "close",
            "close-sheet": "close",
            "toggle-notes": "note",
            "edit-sheet": "edit",
            "export-pdf": "download",
            "delete-sheet": "trash"
        };
        var name = mapping[node.getAttribute("data-action")];
        if (name) {
            node.innerHTML = icon(name);
        }
    });
}

export function measure() {
    var html = document.documentElement;
    html.classList.add("no-animation");
    elements.root.style.setProperty("--rx", "0px");
    elements.root.style.setProperty("--ry", "0px");

    var nodes = [elements.bar];
    elements.root.querySelectorAll(".panel, .toast").forEach(function (node) {
        if (!node.classList.contains("is-hidden")) {
            nodes.push(node);
        }
    });

    var conflictModal = document.getElementById("custom-conflict-modal");
    if (conflictModal) {
        nodes.push(conflictModal);
    }

    var minLeft = Infinity;
    var minTop = Infinity;
    var maxRight = -Infinity;
    var maxBottom = -Infinity;

    nodes.forEach(function (node) {
        var rect = node.getBoundingClientRect();
        if (rect.width === 0 && rect.height === 0) {
            return;
        }
        minLeft = Math.min(minLeft, rect.left);
        minTop = Math.min(minTop, rect.top);
        maxRight = Math.max(maxRight, rect.right);
        maxBottom = Math.max(maxBottom, rect.bottom);
    });

    var barRect = elements.bar.getBoundingClientRect();
    html.classList.remove("no-animation");
    if (!isFinite(minLeft)) {
        return;
    }

    var offsetX = SHADOW_PADDING - minLeft;
    var offsetY = SHADOW_PADDING - minTop;
    elements.root.style.setProperty("--rx", offsetX + "px");
    elements.root.style.setProperty("--ry", offsetY + "px");

    bridge.call("resize", JSON.stringify({
        width: Math.ceil(maxRight - minLeft) + SHADOW_PADDING * 2,
        height: Math.ceil(maxBottom - minTop) + SHADOW_PADDING * 2,
        barLeft: Math.round(barRect.left + offsetX),
        barTop: Math.round(barRect.top + offsetY)
    }));
}

export function scheduleMeasure() {
    if (measureQueued) {
        return;
    }
    measureQueued = true;
    window.requestAnimationFrame(function () {
        measureQueued = false;
        measure();
    });
}

export function openPanel(name) {
    var panels = {
        "add-menu": elements["add-menu"],
        "settings-menu": elements["settings-menu"],
        assistant: elements.assistant,
        editor: elements.editor,
        "trash-panel": elements["trash-panel"]
    };
    Object.keys(panels).forEach(function (key) {
        if (panels[key]) {
            panels[key].classList.toggle("is-hidden", key !== name);
        }
    });
    state.activePanel = name;
    elements["add-toggle"].classList.toggle("active", name === "add-menu" || name === "editor");
    elements["assistant-toggle"].classList.toggle("active", name === "assistant");
    elements["settings-toggle"].classList.toggle("active", name === "settings-menu" || name === "trash-panel");
    if (name === "add-menu") {
        updateRetryOrphansButton();
    }
    scheduleMeasure();
}

function updateRetryOrphansButton() {
    var button = elements["retry-orphans"];
    if (!button) {
        return;
    }
    var count = bridge.json("orphanCount");
    var value = typeof count === "number" ? count : 0;
    if (value > 0) {
        button.querySelector("span").textContent = "Eksik Kayıtları Tamamla (" + value + ")";
        button.classList.remove("is-hidden");
    } else {
        button.classList.add("is-hidden");
    }
}

export function closePanels() {
    openPanel(null);
}

export function hideResults() {
    elements.results.classList.add("is-hidden");
    state.results = [];
    state.focusedIndex = -1;
    scheduleMeasure();
}

export function notify(message, success, persistent) {
    if (!message) {
        return;
    }
    elements.toast.textContent = message;
    elements.toast.classList.toggle("error", !success);
    elements.toast.classList.remove("is-hidden");
    scheduleMeasure();
    if (toastTimer) {
        window.clearTimeout(toastTimer);
        toastTimer = null;
    }
    if (persistent) {
        return;
    }
    toastTimer = window.setTimeout(function () {
        elements.toast.classList.add("is-hidden");
        scheduleMeasure();
    }, TOAST_DURATION);
}

export function notifyWithAction(message, filePath) {
    if (!message) {
        return;
    }
    elements.toast.innerHTML = '<span>' + escapeHtml(message) + '</span>' +
        '<button type="button" class="toast-action-btn" id="toast-open-btn">Aç</button>';
    elements.toast.classList.remove("error");
    elements.toast.classList.remove("is-hidden");
    scheduleMeasure();
    if (toastTimer) {
        window.clearTimeout(toastTimer);
        toastTimer = null;
    }
    var openBtn = document.getElementById("toast-open-btn");
    if (openBtn) {
        openBtn.onclick = function () {
            bridge.call("openFile", filePath);
            elements.toast.classList.add("is-hidden");
            scheduleMeasure();
        };
    }
    toastTimer = window.setTimeout(function () {
        elements.toast.classList.add("is-hidden");
        scheduleMeasure();
    }, 8000);
}

function injectTrashUI() {
    var settingsMenu = elements["settings-menu"];
    if (settingsMenu && !byId("btn-open-trash")) {
        var btn = document.createElement("button");
        btn.type = "button";
        btn.id = "btn-open-trash";
        btn.className = "menu-item";
        btn.setAttribute("data-action", "open-trash");
        btn.innerHTML = '<span>Son Silinenler</span>'; 

        var pinRow = settingsMenu.querySelector(".switch-row");
        if (pinRow && pinRow.nextSibling) {
            settingsMenu.insertBefore(btn, pinRow.nextSibling);
        } else {
            settingsMenu.appendChild(btn);
        }
    }

    var quitBtn = settingsMenu ? settingsMenu.querySelector('[data-action="quit"]') : null;
    if (settingsMenu && quitBtn) {
        settingsMenu.appendChild(quitBtn);
    }

    if (!byId("trash-panel")) {
        var panel = document.createElement("div");
        panel.id = "trash-panel";
        panel.className = "panel panel-menu is-hidden";
        panel.style.width = "340px";
        panel.style.padding = "0";
        
        var header = document.createElement("div");
        header.style.cssText = "padding: 12px 16px; font-weight: 600; font-size: 14px; border-bottom: 1px solid rgba(128,128,128,0.2); display: flex; justify-content: space-between; align-items: center;";
        header.innerHTML = '<span>Son Silinenler</span><button type="button" data-action="close-trash-to-settings" style="background:none; border:none; cursor:pointer; color:inherit; font-weight:bold; font-size:18px; line-height:1; padding:0 4px;">&times;</button>';
        
        var listContainer = document.createElement("div");
        listContainer.id = "trash-list";
        listContainer.style.cssText = "max-height: 350px; overflow-y: auto; padding: 4px 0;";
        
        panel.appendChild(header);
        panel.appendChild(listContainer);
        
        elements.root.appendChild(panel);
        elements["trash-panel"] = panel;
    }
}

function injectAssistantUploadUI() {
    var textInput = byId("assistant-text");
    if (!textInput || byId("assistant-upload-btn")) return;
    
    var assistantBar = textInput.parentNode;
    assistantBar.style.display = "flex";
    assistantBar.style.alignItems = "center";
    assistantBar.style.position = "relative";

    var uploadBtn = document.createElement("button");
    uploadBtn.type = "button";
    uploadBtn.id = "assistant-upload-btn";
    uploadBtn.innerHTML = icon("attachment");
    uploadBtn.setAttribute("title", "Dosya veya Fotoğraf Yükle");
    uploadBtn.style.background = "none";
    uploadBtn.style.border = "none";
    uploadBtn.style.cursor = "pointer";
    uploadBtn.style.padding = "4px 8px";
    uploadBtn.style.display = "flex";
    uploadBtn.style.alignItems = "center";
    uploadBtn.style.justifyContent = "center";
    uploadBtn.style.color = "inherit";

    var fileInput = document.createElement("input");
    fileInput.type = "file";
    fileInput.id = "ast-hidden-file-input";
    fileInput.accept = ".pdf,image/*";
    fileInput.style.display = "none";

    var previewContainer = document.createElement("div");
    previewContainer.id = "assistant-preview-container";
    previewContainer.className = "assistant-preview is-hidden";
    previewContainer.style.padding = "6px 12px";
    previewContainer.style.fontSize = "12px";
    previewContainer.style.display = "flex";
    previewContainer.style.justifyContent = "space-between";
    previewContainer.style.alignItems = "center";
    previewContainer.style.background = "rgba(193, 0, 7, 0.1)";
    previewContainer.style.borderTop = "1px solid rgba(193, 0, 7, 0.2)";

    assistantBar.insertBefore(uploadBtn, textInput);
    assistantBar.appendChild(fileInput);
    
    var assistantPanel = byId("assistant");
    if (assistantPanel) {
        assistantPanel.insertBefore(previewContainer, elements["assistant-log"].nextSibling);
    }

    uploadBtn.addEventListener("click", function (e) {
        e.stopPropagation();
        fileInput.click();
    });

    fileInput.addEventListener("change", function (e) {
        var file = e.target.files[0];
        if (!file) return;
        var reader = new FileReader();
        var isImage = file.type.startsWith("image/");
        reader.onload = function (evt) {
            state.assistantAttachment = {
                type: isImage ? "image" : "file",
                name: file.name,
                data: evt.target.result
            };
            showAttachmentPreview(file.name, isImage ? "image" : "file");
        };
        reader.readAsDataURL(file);
    });
}

export function showAttachmentPreview(name, type) {
    var container = byId("assistant-preview-container");
    if (!container) return;
    var label = type === 'image' ? 'Fotoğraf' : 'Dosya';
    container.innerHTML = '<span>Yüklenen ' + label + ': <strong>' + escapeHtml(name) + '</strong></span>' +
                          '<button type="button" id="ast-remove-attachment" style="background:none; border:none; color:inherit; cursor:pointer; font-weight:bold;">&times;</button>';
    container.classList.remove("is-hidden");
    byId("ast-remove-attachment").addEventListener("click", function () {
        state.assistantAttachment = null;
        container.classList.add("is-hidden");
        container.innerHTML = "";
        byId("ast-hidden-file-input").value = "";
        scheduleMeasure();
    });
    scheduleMeasure();
}
