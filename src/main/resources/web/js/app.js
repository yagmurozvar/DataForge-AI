import { setTexts, elements, t, tf } from './state.js';
import { bridge } from './bridge.js';
import { cacheElements, paintIcons, scheduleMeasure, notify, notifyWithAction } from './ui.js';
import { finishAssistant, appendBubble, openSheet } from './features.js';
import { bindEvents } from './events.js';
import { escapeHtml } from './utils.js';

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

function applyTheme(theme) {
    document.documentElement.setAttribute("data-theme", theme === "dark" ? "dark" : "light");
    elements["theme-switch"].checked = theme === "dark";
}

window.widget = {
    boot: function (payload) {
        var config = payload && typeof payload === "object" ? payload : {};
        setTexts(config.texts && typeof config.texts === "object" ? config.texts : {});
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

function initApp() {
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
}

if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", initApp);
} else {
    initApp();
}
