(function () {
    "use strict";

    var SHADOW_PADDING = 20;
    var SEARCH_DEBOUNCE = 130;
    var TOAST_DURATION = 6000;

    var ICONS = {
        grip: '<circle class="solid" cx="9" cy="6" r="1.35"/><circle class="solid" cx="15" cy="6" r="1.35"/><circle class="solid" cx="9" cy="12" r="1.35"/><circle class="solid" cx="15" cy="12" r="1.35"/><circle class="solid" cx="9" cy="18" r="1.35"/><circle class="solid" cx="15" cy="18" r="1.35"/>',
        search: '<circle cx="11" cy="11" r="6.5"/><path d="M20 20l-4.2-4.2"/>',
        close: '<path d="M6.5 6.5l11 11M17.5 6.5l-11 11"/>',
        plus: '<path d="M12 5.5v13M5.5 12h13"/>',
        bot: '<rect x="3.75" y="8.5" width="16.5" height="11.5" rx="3.5"/><path d="M12 4.5v4M8.5 4.5h7"/><circle class="solid" cx="9" cy="14" r="1.2"/><circle class="solid" cx="15" cy="14" r="1.2"/>',
        more: '<circle class="solid" cx="12" cy="5.5" r="1.6"/><circle class="solid" cx="12" cy="12" r="1.6"/><circle class="solid" cx="12" cy="18.5" r="1.6"/>',
        note: '<rect x="5" y="3.5" width="14" height="17" rx="2.5"/><path d="M8.75 8.5h6.5M8.75 12.5h6.5M8.75 16.5h3.5"/>',
        edit: '<path d="M4.5 19.5h4l9.2-9.2a2.1 2.1 0 0 0 0-3l-1-1a2.1 2.1 0 0 0-3 0L4.5 15.5z"/><path d="M13.5 6.5l4 4"/>',
        download: '<path d="M12 4v10.5"/><path d="M8 11l4 4 4-4"/><path d="M4.5 19.5h15"/>',
        trash: '<path d="M4.5 6.5h15"/><path d="M9.5 6.5V4.8a1.3 1.3 0 0 1 1.3-1.3h2.4a1.3 1.3 0 0 1 1.3 1.3v1.7"/><path d="M6.5 6.5l.9 12.1a1.5 1.5 0 0 0 1.5 1.4h6.2a1.5 1.5 0 0 0 1.5-1.4l.9-12.1"/>',
        send: '<path d="M12 19.5V5.5"/><path d="M6 11.5l6-6 6 6"/>',
        file_open: '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="12" y1="18" x2="12" y2="12"/><polyline points="9 15 12 12 15 15"/>',
        attachment: '<path d="M12 5v14M5 12h14"/>'
    };

    var elements = {};
    var texts = {};
    var state = {
        activePanel: null,
        results: [],
        focusedIndex: -1,
        current: null,
        assistantBusy: false,
        assistantPending: null,
        reopenAssistant: false,
        assistantAttachment: null
    };

    var searchTimer = null;
    var notesTimer = null;
    var toastTimer = null;
    var measureQueued = false;

    var bridge = {
        available: function () {
            return typeof window.app !== "undefined" && window.app !== null;
        },
        call: function (name) {
            if (!this.available() || typeof window.app[name] !== "function") {
                return null;
            }
            var args = Array.prototype.slice.call(arguments, 1);
            try {
                return window.app[name].apply(window.app, args);
            } catch (error) {
                console.error(name, error);
                return null;
            }
        },
        json: function () {
            var raw = this.call.apply(this, arguments);
            if (raw === null || raw === undefined) {
                return null;
            }
            try {
                return JSON.parse(raw);
            } catch (error) {
                console.error(error);
                return null;
            }
        }
    };

    function byId(id) {
        return document.getElementById(id);
    }

    function t(key) {
        return Object.prototype.hasOwnProperty.call(texts, key) ? texts[key] : key;
    }

    function tf(key, values) {
        var result = t(key);
        Object.keys(values || {}).forEach(function (name) {
            result = result.split("{" + name + "}").join(String(values[name]));
        });
        return result;
    }

    function applyTranslations() {
        document.title = t("app.title");
        document.querySelectorAll("[data-i18n]").forEach(function (node) {
            node.textContent = t(node.getAttribute("data-i18n"));
        });
        document.querySelectorAll("[data-i18n-title]").forEach(function (node) {
            node.setAttribute("title", t(node.getAttribute("data-i18n-title")));
        });
        document.querySelectorAll("[data-i18n-aria]").forEach(function (node) {
            node.setAttribute("aria-label", t(node.getAttribute("data-i18n-aria")));
        });
        document.querySelectorAll("[data-i18n-placeholder]").forEach(function (node) {
            node.setAttribute("placeholder", t(node.getAttribute("data-i18n-placeholder")));
        });
    }

    function icon(name) {
        return '<svg class="icon" viewBox="0 0 24 24" aria-hidden="true">' + (ICONS[name] || "") + "</svg>";
    }

    function escapeHtml(value) {
        return String(value === null || value === undefined ? "" : value)
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;");
    }

    function buildSheetMarkup(datasheet, notes) {
        var specs = (datasheet.specs || []).map(function (spec) {
            return "<tr><th>" + escapeHtml(spec.label) + "</th><td>" + escapeHtml(spec.value) + "</td></tr>";
        }).join("");
        var safety = (datasheet.safety || []).map(function (line) {
            return "<li>" + escapeHtml(line) + "</li>";
        }).join("");
        
        var notesBlock = notes && notes.trim()
            ? '<section class="sheet-noteblock"><h2 class="sheet-band" style="background-color: #c10007 !important; color: #ffffff !important;">' + escapeHtml(t("sheet.notes")) + "</h2><p>" +
              escapeHtml(notes).replace(/\n/g, "<br>") + "</p></section>"
            : "";
        
        var imageBlock = (datasheet.image && datasheet.image.trim() !== "")
            ? '<img src="' + escapeHtml(datasheet.image) + '" style="width:100%; max-height:260px; object-fit:contain; margin-bottom:20px; display:block;">'
            : "";

        var pilsanLogoSvg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1000 243.81" style="height: 32px; width: auto;"><style>.st0{fill:#c10007;}</style><g><path d="M550.6,94.26c0,17.41-4.31,31.32-12.89,41.63s-19.91,15.51-33.9,15.51h-30.22v39.86h-28.28V42.18h55.75c15.77,0,28.07,4.9,36.9,14.75,8.41,9.43,12.64,21.85,12.64,37.32h0ZM522.32,95.95c0-9.13-2.79-16.61-8.33-22.44-5.54-5.83-12.47-8.75-20.75-8.75h-19.66v63.66h21.64c8.03,0,14.54-3,19.57-8.96,5.03-5.96,7.52-13.82,7.52-23.5h.01Z"/><path d="M594.3,29.97h-27.69V4.02h27.69s0,25.95,0,25.95ZM594.3,151.41h-27.69V42.18h27.69v109.22h0Z"/><path d="M643.04,151.41h-28.28V4.02h28.28v147.39Z"/><path d="M759.53,118.1c0,9.3-2.83,17.2-8.54,23.84-5.71,6.59-12.89,9.89-21.56,9.89h-68.77v-19.82h56.39c4.02,0,7.35-1.06,10.1-3.17s4.1-5.11,4.1-9.05c0-9.13-5.88-13.7-17.58-13.7h-28.79c-6.13,0-12.05-3.09-17.8-9.3-5.75-6.17-8.58-12.85-8.58-20.04,0-10.1,2.11-18.26,6.42-24.47,4.95-7.02,12.22-10.52,21.85-10.52h66.19v20.67h-58.97c-2.28,0-4.23,1.14-5.92,3.47s-2.49,4.95-2.49,7.9c0,3.25,1.1,6,3.3,8.33s4.82,3.47,7.9,3.47h31.07c9.76,0,17.46,3.04,23.16,9.17,5.66,6.13,8.54,13.86,8.54,23.29h0l-.04.04h.02Z"/><path d="M877.04,151.41h-74.99c-8.41,0-15.6-2.96-21.56-8.92-5.96-5.96-8.92-13.48-8.92-22.57s2.71-17.41,8.12-23.63c5.41-6.21,12.17-9.34,20.29-9.34h48.4v-5.92c0-1.82-.3-3.42-.89-4.73-.59-1.35-1.56-2.92-2.92-4.73-1.86-2.24-3.8-3.68-5.79-4.31s-4.78-.93-8.41-.93h-51.78v-24.05h53.13c9.76,0,17.03.89,21.85,2.62,4.82,1.73,8.96,4.4,12.43,7.9,4.4,4.65,7.35,9.68,8.83,15.13,1.48,5.45,2.2,12.47,2.2,21.05v62.52-.08h0ZM848.38,129.05v-21.3h-35.29c-3.34,0-6.17,1.01-8.54,3.04-2.32,2.03-3.51,4.69-3.51,8.03,0,2.92,1.14,5.37,3.42,7.31,2.28,1.94,4.86,2.92,7.82,2.92h36.1Z"/><path d="M993.96,151.41h-27.86v-71.9c0-1.82-.42-3.47-1.23-4.95-.8-1.48-1.94-2.92-3.42-4.31-2.66-2.41-5.75-3.59-9.21-3.59h-27.26v84.75h-27.86V42.18h53.34c9.47,0,16.36.55,20.63,1.69,6.68,1.69,12.05,5.41,16.06,11.16,4.52,6.76,6.81,14.12,6.81,22.15v74.22h0Z"/><path class="st0" d="M564.8,242.71h-24.9c-6.04,0-11.33-2.75-15.77-8.2s-6.68-11.79-6.68-18.94c0-7.78,2.45-14.54,7.31-20.2,4.86-5.71,10.27-8.54,16.27-8.54h23.67v11.5h-17.37c-2.2,0-4.02.21-5.58.59-1.52.38-2.96,1.14-4.23,2.28-2.37,2.16-3.59,5.03-3.59,8.71h30.6v11.58h-30.6c0,1.73.17,3.17.55,4.35.38,1.18,1.14,2.37,2.28,3.55s2.54,1.99,4.14,2.37,3.55.59,5.88.59h17.92v10.4h.08v-.04h.02Z"/><path class="st0" d="M589.19,242.37h-14.41v-75.03h14.41v75.03Z"/><path class="st0" d="M644.6,242.71h-24.9c-6.04,0-11.33-2.75-15.77-8.2s-6.68-11.79-6.68-18.94c0-7.78,2.45-14.54,7.31-20.2,4.86-5.71,10.27-8.54,16.27-8.54h23.67v11.5h-17.37c-2.2,0-4.02.21-5.58.59-1.52.38-2.96,1.14-4.23,2.28-2.37,2.16-3.59,5.03-3.59,8.71h30.6v11.58h-30.6c0,1.73.17,3.17.55,4.35.38,1.18,1.14,2.37,2.28,3.55s2.54,1.99,4.14,2.37c1.61.38,3.55.59,5.88.59h17.92v10.4h.08v-.04h.02Z"/><path class="st0" d="M668.99,242.37h-14.41v-75.03h14.41v75.03ZM711.68,242.37h-20.08l-21.9-27.05,21.98-28.74h19.87l-23.88,28.24,23.97,27.6h0l.04-.04h0Z"/><path class="st0" d="M746.68,198.37h-13.78v25.23c0,2.28.76,4.18,2.24,5.71,1.69,1.52,3.93,2.24,6.72,2.24h4.18v10.82h-13.19c-3.93,0-7.31-1.52-10.1-4.52-1.65-1.78-2.71-3.76-3.17-5.92-.46-2.16-.72-4.69-.72-7.61v-25.95h-9v-11.58h9v-19.44h13.99v19.44h13.78v11.58h.05Z"/><path class="st0" d="M784.05,198.83h-15.81v43.58h-14.08v-55.58h14.08v7.74c.76-1.94,1.69-3.51,2.79-4.73,1.1-1.23,2.54-2.16,4.31-2.79.8-.3,1.69-.55,2.62-.76s1.86-.34,2.83-.34c1.18,0,2.24.13,3.3.42v12.47h-.04Z"/><path class="st0" d="M850.58,214.43c0,9.68-2.92,17.08-8.79,22.28-5.37,4.73-12.68,7.1-21.94,7.1s-16.44-2.16-21.51-6.47c-5.92-4.95-8.88-12.64-8.88-23.12,0-8.75,3.21-15.64,9.6-20.63,5.71-4.52,12.77-6.76,21.13-6.76,9.17,0,16.53,2.41,22.11,7.27,5.54,4.82,8.33,11.62,8.33,20.37h0l-.04-.04h-.01ZM834.77,214.3c0-4.52-1.35-8.33-4.02-11.37-2.71-3.04-6.34-4.57-10.95-4.57-4.23,0-7.69,1.56-10.48,4.69-2.75,3.13-4.14,7.06-4.14,11.79,0,5.45,1.39,9.81,4.18,13.02s6.38,4.86,10.82,4.86c5.03,0,8.79-1.69,11.24-5.07,2.24-3.09,3.38-7.52,3.38-13.36h-.04.01Z"/><path class="st0" d="M908.06,242.37h-14.2v-36.6c0-.93-.21-1.78-.59-2.54-.42-.76-.97-1.48-1.73-2.2-1.35-1.23-2.92-1.82-4.69-1.82h-13.86v43.16h-14.2v-55.58h27.14c4.82,0,8.33.3,10.53.85,3.38.85,6.13,2.75,8.16,5.71,2.32,3.42,3.47,7.19,3.47,11.29v37.79h0v-.04l-.03-.02Z"/><path class="st0" d="M932.54,180.57h-14.08v-13.19h14.08v13.19ZM932.54,242.37h-14.08v-55.58h14.08v55.58Z"/><path class="st0" d="M957.35,242.37h-14.37v-75.03h14.37v75.03ZM1000,242.37h-20.08l-21.9-27.05,21.98-28.74h19.87l-23.88,28.24,23.97,27.6h0l.04-.04h0Z"/></g><g><path d="M299.35,0c-65.26,0-118.14,52.88-118.14,118.14s52.88,118.14,118.14,118.14,118.14-52.88,118.14-118.14S364.61,0,299.35,0ZM352.31,127.53h-105.97v-18.81h105.97v18.81Z"/><path class="st0" d="M118.14,0C52.88,0,0,52.88,0,118.14s52.88,118.14,118.14,118.14,118.14-52.88,118.14-118.14S183.41,0,118.14,0ZM171.15,127.53h-43.58v43.58h-18.81v-43.58h-43.58v-18.81h43.58v-43.58h18.81v43.58h43.58v18.81Z"/></g></svg>';

        return '<article class="sheet-doc">' +
            '<header class="sheet-brand">' + pilsanLogoSvg + '</header>' +
            '<div class="sheet-identity"><h1>' + escapeHtml(datasheet.title) + "</h1>" +
            "<p>" + escapeHtml(datasheet.code) + "</p></div>" +
            '<div class="sheet-grid">' +
            '<div class="sheet-column">' +
            (datasheet.description ? '<p class="sheet-description">' + escapeHtml(datasheet.description) + "</p>" : "") +
            (safety ? '<h2 class="sheet-band" style="background-color: #c10007 !important; color: #ffffff !important;">' + escapeHtml(t("sheet.safety")) + '</h2><ul class="sheet-safety">' + safety + "</ul>" : "") +
            "</div>" +
            '<div class="sheet-column">' +
            imageBlock +
            (specs ? '<h2 class="sheet-band" style="background-color: #c10007 !important; color: #ffffff !important;">' + escapeHtml(t("sheet.technicalData")) + '</h2><table class="sheet-specs"><tbody>' + specs + "</tbody></table>" : "") +
            "</div>" +
            "</div>" +
            notesBlock +
            '<footer class="sheet-footer">' +
            "<div><b>" + escapeHtml(t("sheet.phone")) + "</b><span>" + escapeHtml(t("contact.phone")) + "</span></div>" +
            "<div><b>" + escapeHtml(t("sheet.email")) + "</b><span>" + escapeHtml(t("contact.mail")) + "</span><span>" + escapeHtml(t("contact.site")) + "</span></div>" +
            "<div><b>" + escapeHtml(t("sheet.address")) + "</b><span>" + escapeHtml(t("contact.address")) + "</span></div>" +
            "</footer>" +
            "</article>";
    }

    function cacheElements() {
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
            panel.className = "panel panel-menu align-right is-hidden";
            panel.setAttribute("data-no-drag", "true");
            panel.style.width = "340px";
            panel.style.padding = "0";
            
            var header = document.createElement("div");
            header.style.cssText = "padding: 12px 16px; font-weight: 600; font-size: 14px; border-bottom: 1px solid rgba(128,128,128,0.2); display: flex; justify-content: space-between; align-items: center;";
            
            var titleSpan = document.createElement("span");
            titleSpan.textContent = "Son Silinenler";

            var closeBtn = document.createElement("button");
            closeBtn.type = "button";
            closeBtn.style.cssText = "background:none; border:none; cursor:pointer; color:inherit; display:flex; align-items:center; justify-content:center; padding:4px;";
            closeBtn.innerHTML = icon("close");
            
            closeBtn.addEventListener("click", function (e) {
                e.stopPropagation();
                openPanel("settings-menu");
            });

            header.appendChild(titleSpan);
            header.appendChild(closeBtn);
            
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

    function showAttachmentPreview(name, type) {
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

    function paintIcons() {
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

    function measure() {
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

    function scheduleMeasure() {
        if (measureQueued) {
            return;
        }
        measureQueued = true;
        window.requestAnimationFrame(function () {
            measureQueued = false;
            measure();
        });
    }

    function openPanel(name) {
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

    function closePanels() {
        openPanel(null);
    }

    function togglePanel(name) {
        hideResults();
        openPanel(state.activePanel === name ? null : name);
    }

    function setSearchExpanded(expanded) {
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

    function hideResults() {
        elements.results.classList.add("is-hidden");
        state.results = [];
        state.focusedIndex = -1;
        scheduleMeasure();
    }

    function renderResults(items) {
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

    function runSearch(query) {
        if (!query) {
            hideResults();
            return;
        }
        var items = bridge.json("search", query);
        renderResults(Array.isArray(items) ? items : []);
    }

    function moveFocus(delta) {
        if (state.results.length === 0) {
            return;
        }
        state.focusedIndex = (state.focusedIndex + delta + state.results.length) % state.results.length;
        elements.results.querySelectorAll(".result-item").forEach(function (node, index) {
            node.classList.toggle("focused", index === state.focusedIndex);
        });
    }

    function openSheet(id) {
        var datasheet = bridge.json("datasheet", id);
        if (!datasheet || datasheet.ok === false) {
            notify((datasheet && datasheet.message) || t("record.openFailed"), false);
            return;
        }
        state.current = datasheet;
        elements["sheet-title"].textContent = datasheet.code
            ? datasheet.code + " \u2013 " + datasheet.title
            : datasheet.title;
        elements["sheet-body"].innerHTML = buildSheetMarkup(datasheet, "");
        elements["sheet-notes-input"].value = datasheet.notes || "";
        elements["sheet-notes"].classList.toggle("is-hidden", !(datasheet.notes || "").trim());
        elements.sheet.classList.remove("is-hidden");
        closePanels();
        hideResults();
        scheduleMeasure();
    }

    function closeSheet() {
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

    function flushNotes() {
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

    function loadTrash() {
        var listNode = byId("trash-list");
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

    function notifyWithAction(message, filePath) {
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

    function exportPdf() {
        if (!state.current) {
            return;
        }
        flushNotes();
        var title = state.current.code || state.current.title || t("common.datasheet");
        bridge.call("exportPdf", JSON.stringify({
            title: title,
            code: state.current.code || "",
            html: buildSheetMarkup(state.current, state.current.notes || "")
        }));
        notify(t("pdf.preparing"), true);
    }

    function requestDelete() {
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

    function openEditor(datasheet) {
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

    function saveDatasheet() {
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

    function uploadDatasheet() {
        closePanels();
        bridge.call("uploadDatasheet");
    }

    function uploadFolder() {
        closePanels();
        bridge.call("uploadFolder");
    }

    function appendBubble(text, from) {
        var bubble = document.createElement("div");
        bubble.className = "bubble from-" + from;
        bubble.textContent = text;
        elements["assistant-log"].appendChild(bubble);
        elements["assistant-log"].scrollTop = elements["assistant-log"].scrollHeight;
        return bubble;
    }

    function setAssistantBusy(busy) {
        state.assistantBusy = busy;
        elements["assistant-text"].disabled = busy;
        elements["assistant-send"].disabled = busy;
    }

    function sendAssistantMessage() {
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
        var previewContainer = byId("assistant-preview-container");
        if (previewContainer) {
            previewContainer.classList.add("is-hidden");
            previewContainer.innerHTML = "";
        }

        state.assistantPending = appendBubble(t("assistant.thinking"), "assistant");
        state.assistantPending.classList.add("pending");
        setAssistantBusy(true);
        bridge.call("askAssistant", JSON.stringify(payloadObj));
    }

    function finishAssistant(text, error) {
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

    function resizeAssistantInput() {
        var input = elements["assistant-text"];
        input.style.height = "auto";
        input.style.height = Math.min(input.scrollHeight, 96) + "px";
    }

    function notify(message, success, persistent) {
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

    function applyTheme(theme) {
        document.documentElement.setAttribute("data-theme", theme === "dark" ? "dark" : "light");
        elements["theme-switch"].checked = theme === "dark";
    }

    function bindEvents() {
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
            }, SEARCH_DEBOUNCE);
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

    window.widget = {
        boot: function (payload) {
            var config = payload && typeof payload === "object" ? payload : {};
            texts = config.texts && typeof config.texts === "object" ? config.texts : {};
            applyTranslations();
            applyTheme(config.theme || "light");
            elements["pin-switch"].checked = config.alwaysOnTop !== false;
            appendBubble(tf("assistant.greeting", { count: config.catalogSize || 0 }), "assistant");
            scheduleMeasure();
        },
        remeasure: function () {
            scheduleMeasure();
        },
        setPlacement: function (openDown) {
            elements.root.classList.toggle("open-down", openDown === true);
            scheduleMeasure();
        },
        notify: function (message, success) {
            notify(message, success !== false);
        },
        assistantReply: function (message) {
            finishAssistant(message || t("assistant.invalidResponse"), false);
        },
        assistantError: function (message) {
            finishAssistant(message || t("assistant.unavailable"), true);
        },
        datasheetImportStarted: function (msg) {
            notify(msg || t("import.analyzing"), true, true);
        },
        datasheetImportFailed: function (message) {
            notify(message || t("import.failed"), false);
        },
        datasheetImported: function (datasheet, updated) {
            notify(updated ? t("import.updated") : t("import.added"), true);
            if (datasheet && datasheet.id) {
                openSheet(datasheet.id);
            }
        },
        datasheetConflict: function (payload) {
            var data = typeof payload === "string" ? JSON.parse(payload) : payload;
            
            var existingModal = document.getElementById("custom-conflict-modal");
            if (existingModal) {
                existingModal.remove();
            }

            var modalOverlay = document.createElement("div");
            modalOverlay.id = "custom-conflict-modal";
            modalOverlay.setAttribute("data-no-drag", "true");
            modalOverlay.style.cssText = "position: absolute; bottom: calc(100% + 10px); left: 0; z-index: 999999; font-family: 'Segoe UI', sans-serif;";

            var titleText = data.existingTitle || data.code || "Aynı koda sahip dosya";
            
            modalOverlay.innerHTML = `
                <div style="background: #f0f0f0; border: 1px solid #999; box-shadow: 0 4px 16px rgba(0,0,0,0.3); width: 440px; border-radius: 4px; overflow: hidden; -webkit-app-region: no-drag;">
                    <div style="background: linear-gradient(to right, #0055ea, #1084ff); color: white; padding: 6px 10px; font-size: 13px; font-weight: 600; display: flex; justify-content: space-between; align-items: center;">
                        <span>Pilsan Datasheet - Çakışma Onayı</span>
                    </div>
                    <div style="padding: 20px 16px; font-size: 13px; color: #000; display: flex; gap: 16px; align-items: flex-start;">
                        <div style="font-size: 28px; color: #e51400; flex-shrink: 0; line-height: 1;">⚠️</div>
                        <div style="line-height: 1.5;">
                            Sistemde <strong>"${escapeHtml(titleText)}"</strong> adında bir ürün zaten kayıtlı.<br><br>Eski dosyayı silip, yapay zekanın hazırladığı güncel ve akıllı versiyonu üzerine yazmak istiyor musunuz?
                        </div>
                    </div>
                    <div style="background: #e1e1e1; padding: 10px 16px; display: flex; justify-content: flex-end; gap: 8px; border-top: 1px solid #dcdcdc;">
                        <button type="button" id="conflict-overwrite-btn" style="min-width: 85px; padding: 5px 12px; background: #0078d7; color: white; border: 1px solid #005a9e; border-radius: 3px; font-size: 12px; cursor: pointer;">Üzerine Yaz</button>
                        <button type="button" id="conflict-cancel-btn" style="min-width: 75px; padding: 5px 12px; background: #e1e1e1; color: #000; border: 1px solid #adadad; border-radius: 3px; font-size: 12px; cursor: pointer;">İptal</button>
                    </div>
                </div>
            `;

            if (elements.root) {
                elements.root.appendChild(modalOverlay);
            } else {
                document.body.appendChild(modalOverlay);
            }

            scheduleMeasure();

            document.getElementById("conflict-cancel-btn").addEventListener("click", function (e) {
                e.stopPropagation();
                modalOverlay.remove();
                scheduleMeasure();
                notify("Üzerine yazma işlemi iptal edildi.", false);
            });

            document.getElementById("conflict-overwrite-btn").addEventListener("click", function (e) {
                e.stopPropagation();
                modalOverlay.remove();
                scheduleMeasure();
                
                var delResult = bridge.json("deleteDatasheet", data.existingId);
                if (delResult && delResult.ok) {
                    var saveResult = bridge.json("saveDatasheet", data.newDatasheetJson);
                    if (saveResult && saveResult.ok) {
                        notify("Eski kayıt silindi, yeni datasheet başarıyla güncellendi.", true);
                        var parsedNew = typeof data.newDatasheetJson === "string" ? JSON.parse(data.newDatasheetJson) : data.newDatasheetJson;
                        var savedId = (saveResult.datasheet && saveResult.datasheet.id) ? saveResult.datasheet.id : parsedNew.id;
                        if (savedId) {
                            openSheet(savedId);
                        }
                    } else {
                        notify((saveResult && saveResult.message) || "Yeni dosya kaydedilirken bir hata oluştu.", false);
                    }
                } else {
                    notify((delResult && delResult.message) || "Eski dosya silinemediği için işlem iptal edildi.", false);
                }
            });
        },
        pdfExportCompleted: function (payload) {
            var data = typeof payload === "string" ? JSON.parse(payload) : payload;
            notifyWithAction(data.message || "PDF indirilenler klasörüne indirildi", data.path);
        },
        isDragRegion: function (x, y) {
            var target = document.elementFromPoint(x, y);
            if (!target) {
                return false;
            }
            if (target.closest("[data-no-drag]")) {
                return false;
            }
            return !!target.closest("[data-drag]");
        }
    };

    document.addEventListener("DOMContentLoaded", function () {
        cacheElements();
        paintIcons();
        bindEvents();
        scheduleMeasure();

        setInterval(function() {
            document.querySelectorAll(".sheet-band").forEach(function(el) {
                el.style.setProperty("background-color", "#c10007", "important");
                el.style.setProperty("color", "#ffffff", "important");
            });
        }, 200);
    });
}());



