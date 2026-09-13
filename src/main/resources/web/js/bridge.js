export const bridge = {
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