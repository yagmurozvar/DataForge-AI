module com.pilsan.datasheet {
    requires com.fasterxml.jackson.annotation;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.databind;
    requires java.desktop;
    requires java.net.http;
    requires javafx.controls;
    requires javafx.web;
    requires jdk.jsobject;

    exports com.pilsan.datasheet;
    opens com.pilsan.datasheet to com.fasterxml.jackson.databind;

    provides java.lang.System.LoggerFinder with com.pilsan.datasheet.AppLoggerFinder;
}
