package com.pilsan.datasheet;

import java.net.URL;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.regex.Pattern;
import javafx.concurrent.Worker;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import netscape.javascript.JSObject;

public final class WidgetWindow {

    private static final System.Logger LOGGER = System.getLogger(WidgetWindow.class.getName());
    private static final String PAGE_RESOURCE = "/web/index.html";
    private static final String BRIDGE_NAME = "app";
    private static final double INITIAL_WIDTH = 340;
    private static final double INITIAL_HEIGHT = 92;
    private static final double EDGE_MARGIN = 24;
    private static final double MIN_VISIBLE_WIDTH = 96;
    private static final double MIN_VISIBLE_HEIGHT = 48;
    private static final Pattern JS_FUNCTION_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    private final Stage stage;
    private final Settings settings;
    private final WebView webView;
    private final WebEngine engine;
    private final AppBridge bridge;

    private double anchorX;
    private double anchorY;
    private double barOffsetX;
    private double barOffsetY;
    private boolean pageReady;
    private boolean dragging;
    private double dragOffsetX;
    private double dragOffsetY;

    public WidgetWindow(
            Stage stage,
            Settings settings,
            DatasheetRepository repository,
            GeminiAssistantService assistant,
            PdfExportService pdfExport,
            GeminiDatasheetService gemini) {

        this.stage = Objects.requireNonNull(stage, "stage");
        this.settings = Objects.requireNonNull(settings, "settings");
        webView = new WebView();
        engine = webView.getEngine();
        bridge = new AppBridge(this, repository, assistant, pdfExport, gemini, settings);

        configureStage();
        configureWebView();
        configureDragging();
        restoreAnchor();
    }

    public void show() {
        URL page = WidgetWindow.class.getResource(PAGE_RESOURCE);
        if (page == null) {
            throw new IllegalStateException(I18n.format("window.resourceMissing", "resource", PAGE_RESOURCE));
        }

        engine.load(page.toExternalForm());
        stage.show();
    }

    public void persistState() {
        clampAnchor();
        settings.anchor(anchorX, anchorY);
        settings.saveQuietly();
    }

    public Window owner() {
        return stage;
    }

    public void applyMeasuredSize(double width, double height, double barLeft, double barTop) {
        if (width <= 0 || height <= 0) {
            return;
        }

        Rectangle2D bounds = currentScreen().getVisualBounds();
        double safeWidth = Math.min(Math.max(1, Math.ceil(width)), bounds.getWidth());
        double safeHeight = Math.min(Math.max(1, Math.ceil(height)), bounds.getHeight());

        barOffsetX = clamp(barLeft, 0, safeWidth);
        barOffsetY = clamp(barTop, 0, safeHeight);

        stage.setWidth(safeWidth);
        stage.setHeight(safeHeight);
        applyAnchor();
    }



    public void toggleVisibility() {
        if (stage.isShowing()) {
            hideToTray();
        } else {
            showFromTray();
        }
    }

    public void showFromTray() {
        applyAnchor();
        stage.show();
        stage.toFront();
        stage.requestFocus();
        updatePlacement();
    }

    public void hideToTray() {
        if (!stage.isShowing()) {
            return;
        }
        persistState();
        stage.hide();
    }

    public void alwaysOnTop(boolean value) {
        stage.setAlwaysOnTop(value);
    }

    public void notifyWidget(String message, boolean success) {
        callWidget("notify", message, success);
    }

    public void callWidget(String function, Object... arguments) {
        if (!pageReady || function == null || !JS_FUNCTION_NAME.matcher(function).matches()) {
            return;
        }

        StringJoiner joiner = new StringJoiner(",", "window.widget." + function + "(", ")");
        for (Object argument : arguments) {
            joiner.add(Json.write(argument));
        }

        try {
            engine.executeScript(joiner.toString());
        } catch (RuntimeException exception) {
            LOGGER.log(System.Logger.Level.WARNING, "Arayüz çağrısı başarısız: " + function, exception);
        }
    }

    public boolean isDragRegion(double x, double y) {
        if (!pageReady) {
            return false;
        }

        try {
            Object result = engine.executeScript(
                    "window.widget.isDragRegion(" + Math.round(x) + "," + Math.round(y) + ")");
            return Boolean.TRUE.equals(result);
        } catch (RuntimeException exception) {
            LOGGER.log(System.Logger.Level.DEBUG, "Sürükleme alanı sorgulanamadı.", exception);
            return false;
        }
    }

    private void configureStage() {
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setAlwaysOnTop(settings.alwaysOnTop());
        stage.setResizable(false);
        stage.setTitle(I18n.text("app.title"));
        stage.setWidth(INITIAL_WIDTH);
        stage.setHeight(INITIAL_HEIGHT);
        stage.setOnCloseRequest(event -> {
            event.consume();
            hideToTray();
        });

        StackPane root = new StackPane(webView);
        root.setBackground(null);
        root.setStyle("-fx-background-color: transparent;");

        Scene scene = new Scene(root, INITIAL_WIDTH, INITIAL_HEIGHT);
        scene.setFill(Color.TRANSPARENT);
        stage.setScene(scene);
    }

    private void configureWebView() {
        webView.setContextMenuEnabled(false);
        webView.setPageFill(Color.TRANSPARENT);
        webView.setMinSize(0, 0);
        webView.setPrefSize(Region.USE_COMPUTED_SIZE, Region.USE_COMPUTED_SIZE);
        webView.setStyle("-fx-background-color: transparent;");

        engine.setJavaScriptEnabled(true);
        engine.setOnAlert(event ->
                LOGGER.log(System.Logger.Level.INFO, "Arayüz bildirimi: " + event.getData()));
        engine.setOnError(event ->
                LOGGER.log(System.Logger.Level.WARNING, "Arayüz hatası: " + event.getMessage()));

        engine.getLoadWorker().stateProperty().addListener((observable, previous, current) -> {
            if (current == Worker.State.SUCCEEDED) {
                onPageLoaded();
            } else if (current == Worker.State.FAILED) {
                LOGGER.log(
                        System.Logger.Level.ERROR,
                        "Arayüz yüklenemedi.",
                        engine.getLoadWorker().getException());
            }
        });
    }

    private void configureDragging() {
        webView.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            dragging = false;
            if (event.getButton() != MouseButton.PRIMARY || !isDragRegion(event.getX(), event.getY())) {
                return;
            }

            dragging = true;
            dragOffsetX = event.getScreenX() - stage.getX();
            dragOffsetY = event.getScreenY() - stage.getY();
            event.consume();
        });

        webView.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (!dragging) {
                return;
            }

            stage.setX(event.getScreenX() - dragOffsetX);
            stage.setY(event.getScreenY() - dragOffsetY);
            event.consume();
        });

        webView.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            if (!dragging) {
                return;
            }

            dragging = false;
            event.consume();
            anchorX = stage.getX() + barOffsetX;
            anchorY = stage.getY() + barOffsetY;
            clampAnchor();
            applyAnchor();
            updatePlacement();
            persistState();
        });
    }

    private void onPageLoaded() {
        JSObject window = (JSObject) engine.executeScript("window");
        window.setMember(BRIDGE_NAME, bridge);
        pageReady = true;
        callWidget("boot", bridge.bootstrapPayload());
        updatePlacement();
    }

    private void updatePlacement() {
        Rectangle2D bounds = currentScreen().getVisualBounds();
        boolean openDown = anchorY < bounds.getMinY() + bounds.getHeight() / 2;
        callWidget("setPlacement", openDown);
    }

    private void restoreAnchor() {
        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        anchorX = settings.anchorX(bounds.getMinX() + EDGE_MARGIN);
        anchorY = settings.anchorY(bounds.getMaxY() - EDGE_MARGIN - MIN_VISIBLE_HEIGHT);
        clampAnchor();
        stage.setX(anchorX);
        stage.setY(anchorY);
    }

    private void applyAnchor() {
        clampAnchor();
        Rectangle2D bounds = currentScreen().getVisualBounds();

        double desiredX = anchorX - barOffsetX;
        double desiredY = anchorY - barOffsetY;

        double maximumX = bounds.getMaxX() - stage.getWidth();
        double maximumY = bounds.getMaxY() - stage.getHeight();

        stage.setX(clamp(desiredX, bounds.getMinX(), Math.max(bounds.getMinX(), maximumX)));
        stage.setY(clamp(desiredY, bounds.getMinY(), Math.max(bounds.getMinY(), maximumY)));
    }

    private void clampAnchor() {
        Rectangle2D bounds = currentScreen().getVisualBounds();
        anchorX = clamp(anchorX, bounds.getMinX(), bounds.getMaxX() - MIN_VISIBLE_WIDTH);
        anchorY = clamp(anchorY, bounds.getMinY(), bounds.getMaxY() - MIN_VISIBLE_HEIGHT);
    }

    private Screen currentScreen() {
        return Screen.getScreensForRectangle(anchorX, anchorY, 1, 1).stream()
                .findFirst()
                .orElseGet(Screen::getPrimary);
    }

    private static double clamp(double value, double minimum, double maximum) {
        if (!Double.isFinite(value)) {
            return minimum;
        }
        if (maximum < minimum) {
            return minimum;
        }
        return Math.min(Math.max(value, minimum), maximum);
    }
}
