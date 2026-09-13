package com.pilsan.datasheet;

import java.awt.AWTException;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import javax.imageio.ImageIO;
import javafx.application.Platform;

public final class TrayService implements AutoCloseable {

    private static final System.Logger LOGGER = System.getLogger(TrayService.class.getName());
    private static final String ICON_RESOURCE = "/tray-icon.png";

    private final SystemTray systemTray;
    private final TrayIcon trayIcon;
    private final WidgetWindow window;
    private final Runnable exitAction;

    private TrayService(SystemTray systemTray, TrayIcon trayIcon, WidgetWindow window, Runnable exitAction) {
        this.systemTray = systemTray;
        this.trayIcon = trayIcon;
        this.window = window;
        this.exitAction = exitAction;
    }

    public static TrayService install(WidgetWindow window, Runnable exitAction) {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(exitAction, "exitAction");

        if (!SystemTray.isSupported()) {
            throw new IllegalStateException(I18n.text("tray.unsupported"));
        }

        java.awt.Image image = loadIcon();
        PopupMenu menu = new PopupMenu();
        MenuItem toggleItem = new MenuItem(I18n.text("tray.toggle"));
        MenuItem exitItem = new MenuItem(I18n.text("tray.exit"));
        menu.add(toggleItem);
        menu.addSeparator();
        menu.add(exitItem);

        TrayIcon trayIcon = new TrayIcon(image, I18n.text("app.title"), menu);
        trayIcon.setImageAutoSize(true);

        SystemTray systemTray = SystemTray.getSystemTray();
        TrayService service = new TrayService(systemTray, trayIcon, window, exitAction);

        toggleItem.addActionListener(event -> service.toggleWindow());
        exitItem.addActionListener(event -> service.exit());
        trayIcon.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getButton() == MouseEvent.BUTTON1 && event.getClickCount() >= 2) {
                    service.toggleWindow();
                }
            }
        });

        try {
            systemTray.add(trayIcon);
        } catch (AWTException exception) {
            throw new IllegalStateException(I18n.text("tray.createFailed"), exception);
        }

        return service;
    }

    private static java.awt.Image loadIcon() {
        try (InputStream stream = TrayService.class.getResourceAsStream(ICON_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException(I18n.format("tray.iconMissing", "resource", ICON_RESOURCE));
            }
            java.awt.Image image = ImageIO.read(stream);
            if (image == null) {
                throw new IllegalStateException(I18n.text("tray.iconReadFailed"));
            }
            return image;
        } catch (IOException exception) {
            throw new IllegalStateException(I18n.text("tray.iconReadFailed"), exception);
        }
    }

    private void toggleWindow() {
        Platform.runLater(window::toggleVisibility);
    }

    private void exit() {
        Platform.runLater(exitAction);
    }

    @Override
    public void close() {
        try {
            systemTray.remove(trayIcon);
        } catch (RuntimeException exception) {
            LOGGER.log(System.Logger.Level.DEBUG, "Sistem tepsisi simgesi kaldırılamadı.", exception);
        }
    }
}
