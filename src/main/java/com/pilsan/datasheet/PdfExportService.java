package com.pilsan.datasheet;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import javafx.concurrent.Worker;
import javafx.print.PageOrientation;
import javafx.print.Paper;
import javafx.print.Printer;
import javafx.print.PrinterJob;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;

public final class PdfExportService {

    private static final System.Logger LOGGER = System.getLogger(PdfExportService.class.getName());
    private static final String PRINT_STYLESHEET = "/web/print.css";
    private static final double A4_WIDTH_PX = 794;
    private static final double A4_HEIGHT_PX = 1123;

    private final String stylesheet;
    private WebView activeView;

    public PdfExportService() {
        stylesheet = loadStylesheet();
    }

    public void export(
            String bodyFragment,
            String documentTitle,
            File destinationFile,
            Consumer<ExportResult> completion) {

        if (activeView != null) {
            completion.accept(new ExportResult(false, I18n.text("pdf.alreadyRunning"), null));
            return;
        }

        WebView view = new WebView();
        view.setContextMenuEnabled(false);
        view.setPrefSize(A4_WIDTH_PX, A4_HEIGHT_PX);

        Group root = new Group(view);
        new Scene(root, A4_WIDTH_PX, A4_HEIGHT_PX);
        activeView = view;

        WebEngine engine = view.getEngine();
        engine.getLoadWorker().stateProperty().addListener((observable, previous, current) -> {
            if (current == Worker.State.SUCCEEDED) {
                ExportResult result = print(engine, documentTitle, destinationFile);
                releaseActiveView();
                completion.accept(result);
            } else if (current == Worker.State.FAILED || current == Worker.State.CANCELLED) {
                releaseActiveView();
                completion.accept(new ExportResult(false, I18n.text("pdf.contentFailed"), null));
            }
        });

        engine.loadContent(buildDocument(bodyFragment, documentTitle), "text/html");
    }

    private ExportResult print(WebEngine engine, String documentTitle, File destinationFile) {
        Printer preferred = preferredPrinter().orElseGet(Printer::getDefaultPrinter);
        if (preferred == null) {
            return new ExportResult(false, I18n.text("pdf.printerMissing"), null);
        }

        PrinterJob job = PrinterJob.createPrinterJob(preferred);
        if (job == null) {
            return new ExportResult(false, I18n.text("pdf.jobFailed"), null);
        }

        job.getJobSettings().setJobName(documentTitle);

        // KAYDET PENCERESİ AÇILMADAN DOĞRUDAN DOSYAYA YAZDIRMAYI SAĞLAYAN KOMUT:
        job.getJobSettings().setOutputFile(destinationFile.getAbsolutePath());

        Printer selected = job.getPrinter();
        job.getJobSettings().setPageLayout(
                selected.createPageLayout(
                        Paper.A4,
                        PageOrientation.PORTRAIT,
                        Printer.MarginType.DEFAULT));

        try {
            engine.print(job);
            if (!job.endJob()) {
                return new ExportResult(false, I18n.text("pdf.queueFailed"), null);
            }
        } catch (RuntimeException exception) {
            LOGGER.log(System.Logger.Level.ERROR, "PDF/yazdırma işlemi başarısız.", exception);
            job.cancelJob();
            return new ExportResult(false, I18n.text("pdf.exportFailed"), null);
        }

        return new ExportResult(true, destinationFile.getName() + " İndirilenler klasörüne indirildi.", destinationFile.getAbsolutePath());
    }

    private Optional<Printer> preferredPrinter() {
        return Printer.getAllPrinters().stream()
                .sorted(Comparator.comparing(Printer::getName, String.CASE_INSENSITIVE_ORDER))
                .filter(printer -> isPdfPrinter(printer.getName()))
                .findFirst();
    }

    private boolean isPdfPrinter(String name) {
        String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return normalized.contains("microsoft print to pdf")
                || normalized.contains("print to pdf")
                || normalized.endsWith(" pdf")
                || normalized.startsWith("pdf ");
    }

    private String buildDocument(String bodyFragment, String documentTitle) {
        return """
                <!DOCTYPE html>
                <html lang="tr">
                <head>
                    <meta charset="UTF-8">
                    <title>%s</title>
                    <style>%s</style>
                </head>
                <body>%s</body>
                </html>
                """.formatted(escapeHtml(documentTitle), stylesheet, bodyFragment);
    }

    private String loadStylesheet() {
        try (InputStream stream = PdfExportService.class.getResourceAsStream(PRINT_STYLESHEET)) {
            if (stream == null) {
                throw new IllegalStateException(I18n.format("pdf.styleMissing", "resource", PRINT_STYLESHEET));
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException(I18n.text("pdf.styleReadFailed"), exception);
        }
    }

    private String escapeHtml(String value) {
        String safe = value == null ? "" : value;
        return safe
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private void releaseActiveView() {
        activeView = null;
    }

    public record ExportResult(boolean success, String message, String filePath) {
    }
}