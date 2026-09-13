import { elements, state } from './state.js';
import { bridge } from './bridge.js';
import { 
    scheduleMeasure, openPanel, closePanels, hideResults, notify, loadTrash, showAttachmentPreview 
} from './ui.js';
import { 
    runSearch, moveFocus, openSheet, closeSheet, flushNotes, exportPdf, 
    requestDelete, openEditor, saveDatasheet, uploadDatasheet, uploadFolder, 
    sendAssistantMessage, resizeAssistantInput 
} from './features.js';

var searchTimer = null;
var notesTimer = null;

export function setSearchExpanded(expanded) {
    elements.bar.classList.toggle("expanded", expanded);
    if (expanded) {
        elements["search-input"].focus();
    } else {
        elements["search-input"].value = "";
        elements["search-clear"].classList.add("is-hidden");
        hideResults();
    }
    scheduleMeasure();
}

function togglePanel(name) {
    hideResults();
    openPanel(state.activePanel === name ? null : name);
}

function applyTheme(theme) {
    document.documentElement.setAttribute("data-theme", theme === "dark" ? "dark" : "light");
    elements["theme-switch"].checked = theme === "dark";
}

export function bindEvents() {
    elements["search-toggle"].addEventListener("click", function () {
        setSearchExpanded(!elements.bar.classList.contains("expanded"));
        closePanels();
    });
    elements["search-input"].addEventListener("input", function (event) {
        var query = event.target.value.trim();
        elements["search-clear"].classList.toggle("is-hidden", query.length === 0);
        if (searchTimer) {
            window.clearTimeout(searchTimer);
        }
        searchTimer = window.setTimeout(function () {
            runSearch(query);
        }, 130);
    });
    elements["search-input"].addEventListener("keydown", function (event) {
        if (event.key === "ArrowDown") {
            event.preventDefault();
            moveFocus(1);
        } else if (event.key === "ArrowUp") {
            event.preventDefault();
            moveFocus(-1);
        } else if (event.key === "Enter") {
            event.preventDefault();
            if (state.focusedIndex >= 0 && state.results[state.focusedIndex]) {
                openSheet(state.results[state.focusedIndex].id);
            }
        } else if (event.key === "Escape") {
            setSearchExpanded(false);
        }
    });
    elements["search-clear"].addEventListener("click", function () {
        elements["search-input"].value = "";
        elements["search-clear"].classList.add("is-hidden");
        hideResults();
        elements["search-input"].focus();
    });
    elements.results.addEventListener("click", function (event) {
        var item = event.target.closest(".result-item");
        if (!item) {
            return;
        }
        var index = Number(item.getAttribute("data-index"));
        if (state.results[index]) {
            openSheet(state.results[index].id);
        }
    });
    elements["add-toggle"].addEventListener("click", function () {
        togglePanel("add-menu");
    });
    elements["assistant-toggle"].addEventListener("click", function () {
        togglePanel("assistant");
        if (state.activePanel === "assistant" && !state.assistantBusy) {
            elements["assistant-text"].focus();
        }
    });
    elements["settings-toggle"].addEventListener("click", function () {
        togglePanel("settings-menu");
    });
    elements["assistant-send"].addEventListener("click", sendAssistantMessage);
    elements["assistant-text"].addEventListener("input", resizeAssistantInput);
    elements["assistant-text"].addEventListener("keydown", function (event) {
        if ((event.key === "Enter" || event.keyCode === 13) && !event.shiftKey && !event.isComposing) {
            event.preventDefault();
            sendAssistantMessage();
        }
    });
    elements["theme-switch"].addEventListener("change", function (event) {
        var theme = event.target.checked ? "dark" : "light";
        applyTheme(theme);
        var result = bridge.json("setTheme", theme);
        if (result && !result.ok) {
            notify(result.message, false);
        }
    });
    elements["pin-switch"].addEventListener("change", function (event) {
        var result = bridge.json("setAlwaysOnTop", String(event.target.checked));
        if (result && !result.ok) {
            notify(result.message, false);
        }
    });
    elements["sheet-notes-input"].addEventListener("input", function () {
        if (notesTimer) {
            window.clearTimeout(notesTimer);
        }
        notesTimer = window.setTimeout(flushNotes, 600);
    });

    elements["editor-image-file"].addEventListener("change", function(event) {
        var file = event.target.files[0];
        if (!file) return;
        var reader = new FileReader();
        reader.onload = function(e) {
            var b64 = e.target.result;
            elements["editor-image-base64"].value = b64;
            elements["editor-image-preview"].src = b64;
            elements["editor-image-preview"].style.display = "block";
            elements["editor-image-clear"].classList.remove("is-hidden");
            scheduleMeasure();
        };
        reader.readAsDataURL(file);
    });

    elements["editor-image-clear"].addEventListener("click", function() {
        elements["editor-image-file"].value = "";
        elements["editor-image-base64"].value = "";
        elements["editor-image-preview"].src = "";
        elements["editor-image-preview"].style.display = "none";
        elements["editor-image-clear"].classList.add("is-hidden");
        scheduleMeasure();
    });

    document.addEventListener("click", function (event) {
        var aiBtn = event.target.closest(".ai-btn");
        if (aiBtn) {
            var sheetId = aiBtn.getAttribute("data-id") || aiBtn.dataset.id;
            if (sheetId) {
                sheetId = sheetId.replace(/["'\\]/g, "").trim();
                if (state.activePanel === "assistant") {
                    state.reopenAssistant = true;
                }
                openSheet(sheetId);
            }
            return;
        }

        var trigger = event.target.closest("[data-action]");
        if (trigger) {
            handleAction(trigger.getAttribute("data-action"));
            return;
        }
        if (state.activePanel && !event.target.closest(".panel") && !event.target.closest(".bar")) {
            closePanels();
        }
        if (!event.target.closest(".bar") && !event.target.closest(".panel-results")) {
            hideResults();
        }
    });
    document.addEventListener("keydown", function (event) {
        if (event.key !== "Escape") {
            return;
        }
        if (state.current) {
            closeSheet();
        } else if (state.activePanel) {
            closePanels();
        } else if (elements.bar.classList.contains("expanded")) {
            setSearchExpanded(false);
        }
    });
    window.addEventListener("resize", scheduleMeasure);
}

function handleAction(action) {
    switch (action) {
        case "open-original":
            if (state.current) {
                bridge.call("openOriginalFile", state.current.id);
            }
            break;
        case "manual-add":
            openEditor(null);
            break;
        case "upload-datasheet":
            uploadDatasheet();
            break;
        case "upload-folder":
            uploadFolder();
            break;
        case "retry-orphans":
            closePanels();
            bridge.call("retryFailedDatasheets");
            break;
        case "open-trash":
            openPanel("trash-panel");
            loadTrash();
            break;
        case "close-trash-to-settings":
            openPanel("settings-menu");
            break;
        case "quit":
            bridge.call("quit");
            break;
        case "close-assistant":
            closePanels();
            break;
        case "close-editor":
            var editorId = elements.editor.dataset.id;
            closePanels();
            if (editorId) {
                openSheet(editorId);
            }
            break;
        case "save-datasheet":
            saveDatasheet();
            break;
        case "close-sheet":
            closeSheet();
            break;
        case "toggle-notes":
            elements["sheet-notes"].classList.toggle("is-hidden");
            break;
        case "edit-sheet":
            if (state.current) {
                var target = state.current;
                state.reopenAssistant = false;
                closeSheet();
                openEditor(target);
            }
            break;
        case "export-pdf":
            exportPdf();
            break;
        case "delete-sheet":
            requestDelete();
            break;
        default:
            break;
    }
}
