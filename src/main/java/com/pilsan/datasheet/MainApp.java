package com.pilsan.datasheet;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

public final class MainApp extends Application {

    private static final System.Logger LOGGER = System.getLogger(MainApp.class.getName());

    private WidgetWindow widget;
    private TrayService tray;

    @Override
    public void start(Stage hostStage) {
        Thread.currentThread().setUncaughtExceptionHandler((thread, throwable) ->
                AppLog.error("JavaFX iş parçacığında yakalanmayan hata: " + thread.getName(), throwable));

        try {
            Platform.setImplicitExit(false);
            configureHostStage(hostStage);

            Settings settings = Settings.load();
            DatasheetRepository repository = DatasheetRepository.open();
            GeminiAssistantService assistant = new GeminiAssistantService(repository);
            PdfExportService pdfExport = new PdfExportService();
            GeminiDatasheetService gemini = new GeminiDatasheetService();

            Stage widgetStage = new Stage();
            widgetStage.initOwner(hostStage);

            widget = new WidgetWindow(widgetStage, settings, repository, assistant, pdfExport, gemini);
            widget.show();
            tray = TrayService.install(widget, this::exitApplication);

            LOGGER.log(System.Logger.Level.INFO, I18n.text("app.title") + " başlatıldı.");
            AppLog.info(I18n.text("app.title") + " başlatıldı. Log=" + AppLog.file());
        } catch (RuntimeException exception) {
            LOGGER.log(System.Logger.Level.ERROR, I18n.text("app.fatalHeader"), exception);
            AppLog.error(I18n.text("app.fatalHeader"), exception);
            showFatalError(exception);
        }
    }

    @Override
    public void stop() {
        if (widget != null) {
            widget.persistState();
        }
        if (tray != null) {
            tray.close();
        }
    }

    public static void main(String[] args) {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) ->
                AppLog.error("Yakalanmayan hata: " + thread.getName(), throwable));
        launch(args);
    }

    private void configureHostStage(Stage stage) {
        stage.initStyle(StageStyle.UTILITY);
        stage.setOpacity(0);
        stage.setWidth(1);
        stage.setHeight(1);
        stage.setX(-10000);
        stage.setY(-10000);
        stage.show();
    }

    private void exitApplication() {
        if (widget != null) {
            widget.persistState();
        }
        Platform.exit();
    }

    private void showFatalError(RuntimeException exception) {
        Alert alert = new Alert(AlertType.ERROR);
        alert.setTitle(I18n.text("app.title"));
        alert.setHeaderText(I18n.text("app.fatalHeader"));
        String message = exception.getMessage();
        alert.setContentText(message == null || message.isBlank()
                ? I18n.text("app.fatalUnexpected")
                : message);
        alert.showAndWait();
        Platform.exit();
    }
}