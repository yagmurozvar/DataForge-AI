package com.pilsan.datasheet;

import java.text.MessageFormat;
import java.util.ResourceBundle;

public final class AppLoggerFinder extends System.LoggerFinder {

    private static final String APP_PACKAGE = "com.pilsan.datasheet";

    @Override
    public System.Logger getLogger(String name, Module module) {
        return new AppLogger(name);
    }

    private static final class AppLogger implements System.Logger {

        private final String name;
        private final boolean applicationScope;

        private AppLogger(String name) {
            this.name = name == null ? "" : name;
            this.applicationScope = this.name.startsWith(APP_PACKAGE);
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean isLoggable(Level level) {
            if (level == Level.OFF) {
                return false;
            }
            int threshold = applicationScope ? Level.INFO.getSeverity() : Level.WARNING.getSeverity();
            return level.getSeverity() >= threshold;
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String message, Throwable throwable) {
            if (!isLoggable(level)) {
                return;
            }
            emit(level, format(bundle, message), throwable);
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String format, Object... params) {
            if (!isLoggable(level)) {
                return;
            }
            emit(level, formatWithParams(bundle, format, params), null);
        }

        private void emit(Level level, String message, Throwable throwable) {
            try {
                String line = name.isEmpty() ? message : name + " - " + message;
                switch (level) {
                    case ERROR -> AppLog.error(line, throwable);
                    case WARNING -> {
                        if (throwable != null) {
                            AppLog.error(line, throwable);
                        } else {
                            AppLog.warning(line);
                        }
                    }
                    default -> {
                        if (throwable != null) {
                            AppLog.error(line, throwable);
                        } else {
                            AppLog.info(line);
                        }
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }

        private String format(ResourceBundle bundle, String message) {
            if (message == null) {
                return "";
            }
            if (bundle != null && bundle.containsKey(message)) {
                return bundle.getString(message);
            }
            return message;
        }

        private String formatWithParams(ResourceBundle bundle, String format, Object... params) {
            String base = format(bundle, format);
            if (params == null || params.length == 0) {
                return base;
            }
            try {
                return MessageFormat.format(base, params);
            } catch (RuntimeException exception) {
                return base;
            }
        }
    }
}
