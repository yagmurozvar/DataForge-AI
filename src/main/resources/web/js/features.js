import { elements, state, t, texts } from './state.js';
import { bridge } from './bridge.js';
import { hideResults, openPanel, closePanels, notify, scheduleMeasure, showAttachmentPreview } from './ui.js';
import { buildSheetMarkup } from './templates.js';
import { escapeHtml } from './utils.js';

var notesTimer = null;

export function renderResults(items) {
    state.results = items;
    state.focusedIndex = items.length > 0 ? 0 : -1;
    if (items.length === 0) {
        elements.results.innerHTML = '<div class="empty-state">' + escapeHtml(t("search.empty")) + "</div>";
    } else {
        elements.results.innerHTML = items.map(function (item, index) {
            var subtitle = item.title || t("common.datasheet");
            return '<button type="button" class="result-item' + (index === 0 ? " focused" : "") +
                '" data-index="' + index + '">' +
                "<strong>" + escapeHtml(item.code || item.title) + "</strong>" +
                "<span>" + escapeHtml(subtitle) + "</span>" +
                "</button>";
        }).join("");
    }
    elements.results.classList.remove("is-hidden");
    scheduleMeasure();
}

export function runSearch(query) {
    if (!query) {
        hideResults();
        return;
    }
    var items = bridge.json("search", query);
    renderResults(Array.isArray(items) ? items : []);
}

export function moveFocus(delta) {
    if (state.results.length === 0) {
        return;
    }
    state.focusedIndex = (state.focusedIndex + delta + state.results.length) % state.results.length;
    elements.results.querySelectorAll(".result-item").forEach(function (node, index) {
        node.classList.toggle("focused", index === state.focusedIndex);
    });
}

export function openSheet(id) {
    var datasheet = bridge.json("datasheet", id);
    if (!datasheet || datasheet.ok === false) {
        notify((datasheet && datasheet.message) || t("record.openFailed"), false);
        return;
    }
    state.current = datasheet;
    elements["sheet-title"].textContent = datasheet.code
        ? datasheet.code + " \u2013 " + datasheet.title
        : datasheet.title;
    elements["sheet-body"].innerHTML = buildSheetMarkup(datasheet, "", texts);
    elements["sheet-notes-input"].value = datasheet.notes || "";
    elements["sheet-notes"].classList.toggle("is-hidden", !(datasheet.notes || "").trim());
    elements.sheet.classList.remove("is-hidden");
    closePanels();
    hideResults();
    scheduleMeasure();
}

export function closeSheet() {
    if (!state.current) {
        return;
    }
    flushNotes();
    elements.sheet.classList.add("is-hidden");
    state.current = null;
    
    if (state.reopenAssistant) {
        state.reopenAssistant = false;
        openPanel("assistant");
    } else {
        scheduleMeasure();
    }
}

export function flushNotes() {
    if (notesTimer) {
        window.clearTimeout(notesTimer);
        notesTimer = null;
    }
    if (!state.current) {
        return true;
    }
    var value = elements["sheet-notes-input"].value;
    if (value === (state.current.notes || "")) {
        return true;
    }
    var result = bridge.json("saveNotes", JSON.stringify({
        id: state.current.id,
        notes: value
    }));
    if (!result || !result.ok) {
        notify((result && result.message) || t("record.notesSaveFailed"), false);
        return false;
    }
    state.current.notes = value;
    return true;
}

export function loadTrash() {
    var listNode = document.getElementById("trash-list");
    if (!listNode) return;
    
    var items = bridge.json("getDeletedDatasheets");
    
    if (!items || !Array.isArray(items) || items.length === 0) {
        listNode.innerHTML = '<div style="padding: 20px; text-align: center; font-size: 13px; color: #888;">Çöp kutusu boş.</div>';
    } else {
        listNode.innerHTML = items.map(function(item) {
            var displayTitle = item.code || item.title || "İsimsiz Dosya";
            return '<div style="display: flex; justify-content: space-between; align-items: center; padding: 10px 16px; border-bottom: 1px solid rgba(128,128,128,0.15);">' +
                   '<div style="overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1; padding-right: 12px; font-size: 13px;">' + escapeHtml(displayTitle) + '</div>' +
                   '<div style="display: flex; gap: 6px; flex-shrink: 0;">' +
                   '<button type="button" class="restore-btn" data-id="' + item.id + '" style="cursor:pointer; background:#0078d7; color:#fff; border:1px solid #005a9e; padding:4px 8px; border-radius:3px; font-size:12px;">Geri Yükle</button>' +
                   '<button type="button" class="hard-delete-btn" data-id="' + item.id + '" style="cursor:pointer; background:#e1e1e1; color:#000; border:1px solid #adadad; padding:4px 8px; border-radius:3px; font-size:12px;">Sil</button>' +
                   '</div></div>';
        }).join("");
        
        listNode.querySelectorAll(".restore-btn").forEach(function(btn) {
            btn.addEventListener("click", function(e) {
                var id = e.target.getAttribute("data-id");
                var res = bridge.json("restoreDatasheet", id);
                if (res && res.ok) {
                    notify("Dosya başarıyla geri yüklendi.", true);
                    loadTrash();
                    runSearch(elements["search-input"].value.trim()); 
                } else {
                    notify((res && res.message) || "Dosya geri yüklenemedi.", false);
                }
            });
        });

        listNode.querySelectorAll(".hard-delete-btn").forEach(function(btn) {
            btn.addEventListener("click", function(e) {
                var id = e.target.getAttribute("data-id");
                var res = bridge.json("hardDeleteDatasheet", id);
                if (res && res.ok) {
                    notify("Dosya kalıcı olarak silindi.", true);
                    loadTrash();
                } else {
                    notify((res && res.message) || "Dosya silinemedi.", false);
                }
            });
        });
    }
    scheduleMeasure();
}

export function exportPdf() {
    if (!state.current) {
        return;
    }
    flushNotes();
    var title = state.current.code || state.current.title || t("common.datasheet");
    bridge.call("exportPdf", JSON.stringify({
        title: title,
        code: state.current.code || "",
        html: buildSheetMarkup(state.current, state.current.notes || "", texts)
    }));
    notify(t("pdf.preparing"), true);
}

export function requestDelete() {
    if (!state.current) {
        return;
    }
    
    var modalId = "win-delete-confirm-modal";
    var existingModal = document.getElementById(modalId);
    if (existingModal) {
        existingModal.remove();
    }

    var modal = document.createElement("div");
    modal.id = modalId;
    modal.setAttribute("data-no-drag", "true");
    modal.style.cssText = "position: absolute; bottom: calc(100% + 10px); left: 0; z-index: 999999; font-family: 'Segoe UI', sans-serif;";
    modal.innerHTML = `
        <div style="background: #f0f0f0; border: 1px solid #999; box-shadow: 0 4px 16px rgba(0,0,0,0.3); width: 380px; border-radius: 4px; overflow: hidden; -webkit-app-region: no-drag;">
            <div style="background: linear-gradient(to right, #0055ea, #1084ff); color: white; padding: 6px 10px; font-size: 13px; font-weight: 600; display: flex; justify-content: space-between; align-items: center;">
                <span>Pilsan Datasheet - Onay</span>
            </div>
            <div style="padding: 20px 16px; font-size: 13px; color: #000; display: flex; gap: 14px; align-items: center;">
                <div style="font-size: 28px; color: #e51400; flex-shrink: 0;">⚠️</div>
                <div>Bu datasheet'i silmek istiyor musunuz? (Çöp Kutusuna Taşınacak)</div>
            </div>
            <div style="background: #e1e1e1; padding: 10px 16px; display: flex; justify-content: flex-end; gap: 8px; border-top: 1px solid #dcdcdc;">
                <button type="button" id="win-del-yes" style="min-width: 75px; padding: 5px 12px; background: #0078d7; color: white; border: 1px solid #005a9e; border-radius: 3px; font-size: 12px; cursor: pointer;">Sil</button>
                <button type="button" id="win-del-no" style="min-width: 75px; padding: 5px 12px; background: #e1e1e1; color: #000; border: 1px solid #adadad; border-radius: 3px; font-size: 12px; cursor: pointer;">Vazgeç</button>
            </div>
        </div>
    `;

    if (elements.root) {
        elements.root.appendChild(modal);
    } else {
        document.body.appendChild(modal);
    }
    scheduleMeasure();

    document.getElementById("win-del-no").addEventListener("click", function (e) {
        e.stopPropagation();
        modal.remove();
        scheduleMeasure();
    });

    document.getElementById("win-del-yes").addEventListener("click", function (e) {
        e.stopPropagation();
        modal.remove();
        scheduleMeasure();

        var recordId = state.current.id;
        var result = bridge.json("deleteDatasheet", recordId);
        if (!result || !result.ok) {
            notify((result && result.message) || t("record.deleteFailed"), false);
            return;
        }
        closeSheet();
        notify(result.message || "Çöp kutusuna taşındı.", true);
        runSearch(elements["search-input"].value.trim());
    });
}

export function openEditor(datasheet) {
    elements["editor-title"].textContent = datasheet ? t("editor.editTitle") : t("editor.newTitle");
    elements.editor.dataset.id = datasheet ? datasheet.id : "";
    elements.editor.dataset.notes = datasheet ? (datasheet.notes || "") : "";
    elements["editor-code"].value = datasheet ? datasheet.code : "";
    elements["editor-category"].value = datasheet ? datasheet.category : "";
    elements["editor-title-input"].value = datasheet ? datasheet.title : "";
    elements["editor-description"].value = datasheet ? datasheet.description : "";
    elements["editor-specs"].value = datasheet
        ? (datasheet.specs || []).map(function (spec) {
            return spec.label + ": " + spec.value;
        }).join("\n")
        : "";
    elements["editor-safety"].value = datasheet ? (datasheet.safety || []).join("\n") : "";
    
    var imgBase64 = datasheet ? (datasheet.image || "") : "";
    elements["editor-image-base64"].value = imgBase64;
    elements["editor-image-file"].value = ""; 
    if (imgBase64) {
        elements["editor-image-preview"].src = imgBase64;
        elements["editor-image-preview"].style.display = "block";
        elements["editor-image-clear"].classList.remove("is-hidden");
    } else {
        elements["editor-image-preview"].src = "";
        elements["editor-image-preview"].style.display = "none";
        elements["editor-image-clear"].classList.add("is-hidden");
    }

    openPanel("editor");
    elements["editor-code"].focus();
}

function parseSpecs(raw) {
    return raw.split("\n").map(function (line) {
        return line.trim();
    }).filter(function (line) {
        return line.length > 0;
    }).map(function (line) {
        var separator = line.indexOf(":");
        if (separator === -1) {
            return { label: line, value: "" };
        }
        return {
            label: line.slice(0, separator).trim(),
            value: line.slice(separator + 1).trim()
        };
    });
}

export function saveDatasheet() {
    var payload = {
        id: elements.editor.dataset.id || "",
        code: elements["editor-code"].value.trim(),
        title: elements["editor-title-input"].value.trim(),
        category: elements["editor-category"].value.trim(),
        description: elements["editor-description"].value.trim(),
        specs: parseSpecs(elements["editor-specs"].value),
        safety: elements["editor-safety"].value.split("\n").map(function (line) {
            return line.trim();
        }).filter(function (line) {
            return line.length > 0;
        }),
        notes: elements.editor.dataset.notes || "",
        image: elements["editor-image-base64"].value || ""
    };
    var result = bridge.json("saveDatasheet", JSON.stringify(payload));
    if (!result || !result.ok) {
        notify((result && result.message) || t("record.saveFailed"), false);
        return;
    }
    
    notify(result.message, true);
    
    var savedId = result.datasheet && result.datasheet.id ? result.datasheet.id : payload.id;
    if (savedId) {
        openSheet(savedId);
    } else {
        closePanels();
    }

    var query = elements["search-input"].value.trim();
    if (query) {
        runSearch(query);
    }
}

export function uploadDatasheet() {
    closePanels();
    bridge.call("uploadDatasheet");
}

export function uploadFolder() {
    closePanels();
    bridge.call("uploadFolder");
}

export function appendBubble(text, from) {
    var bubble = document.createElement("div");
    bubble.className = "bubble from-" + from;
    bubble.textContent = text;
    elements["assistant-log"].appendChild(bubble);
    elements["assistant-log"].scrollTop = elements["assistant-log"].scrollHeight;
    return bubble;
}

export function setAssistantBusy(busy) {
    state.assistantBusy = busy;
    elements["assistant-text"].disabled = busy;
    elements["assistant-send"].disabled = busy;
}

export function sendAssistantMessage() {
    var input = elements["assistant-text"];
    var message = input.value.trim();
    if (!message && !state.assistantAttachment) {
        return;
    }
    if (state.assistantBusy) {
        notify(t("assistant.busy"), false);
        return;
    }

    var displayMsg = message;
    if (state.assistantAttachment) {
        var prefix = state.assistantAttachment.type === 'image' ? 'Fotoğraf' : 'Dosya';
        displayMsg = "[" + prefix + ": " + state.assistantAttachment.name + "] " + message;
    }

    appendBubble(displayMsg, "user");
    input.value = "";
    resizeAssistantInput();

    var payloadObj = {
        text: message,
        attachment: state.assistantAttachment
    };

    state.assistantAttachment = null;
    var previewContainer = document.getElementById("assistant-preview-container");
    if (previewContainer) {
        previewContainer.classList.add("is-hidden");
        previewContainer.innerHTML = "";
    }

    state.assistantPending = appendBubble(t("assistant.thinking"), "assistant");
    state.assistantPending.classList.add("pending");
    setAssistantBusy(true);
    bridge.call("askAssistant", JSON.stringify(payloadObj));
}

export function finishAssistant(text, error) {
    var bubble = state.assistantPending;
    if (!bubble) {
        bubble = appendBubble("", "assistant");
    }
    
    if (error) {
        bubble.textContent = text;
    } else {
        var cleanHtml = text
            .replace(/^```[a-z]*\n?/i, "")
            .replace(/```$/, "")
            .trim();
        
        bubble.innerHTML = cleanHtml.replace(/(?:\r\n|\r|\n)/g, "<br>");
    }
    
    bubble.classList.remove("pending");
    bubble.classList.toggle("error", error === true);
    state.assistantPending = null;
    setAssistantBusy(false);
    elements["assistant-text"].focus();
    elements["assistant-log"].scrollTop = elements["assistant-log"].scrollHeight;
    scheduleMeasure();
}

export function resizeAssistantInput() {
    var input = elements["assistant-text"];
    input.style.height = "auto";
    input.style.height = Math.min(input.scrollHeight, 96) + "px";
}
