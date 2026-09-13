export const elements = {};
export let texts = {};

export const state = {
    activePanel: null,
    results: [],
    focusedIndex: -1,
    current: null,
    assistantBusy: false,
    assistantPending: null,
    reopenAssistant: false,
    assistantAttachment: null
};

export function setTexts(newTexts) {
    texts = newTexts;
}

export function t(key) {
    return Object.prototype.hasOwnProperty.call(texts, key) ? texts[key] : key;
}

export function tf(key, values) {
    var result = t(key);
    Object.keys(values || {}).forEach(function (name) {
        result = result.split("{" + name + "}").join(String(values[name]));
    });
    return result;
}
