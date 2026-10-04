package com.instead.annotation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.instead.annotation.model.AnnotationDocument;
import com.instead.annotation.render.FieldPlanner;
import com.instead.annotation.render.PdfDrawer;
import com.instead.annotation.render.RenderPlan;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

/**
 * CLI entry point for the reference renderer.
 *
 * <pre>
 * java -jar annotation-renderer.jar \
 *     --annotation spec/f1040-page1.annotation.json \
 *     --data data/sample-taxpayer.json \
 *     --pdf forms/f1040.pdf \
 *     --out output/f1040-filled.pdf \
 *     [--debug]
 * </pre>
 */
public final class RenderMain {

    public static void main(String[] args) throws IOException, NoSuchAlgorithmException {
        Map<String, String> flags = parseArgs(args);
        boolean debug = args.length > 0 && java.util.Arrays.asList(args).contains("--debug");

        require(flags, "annotation");
        require(flags, "data");
        require(flags, "pdf");
        require(flags, "out");

        ObjectMapper mapper = new ObjectMapper();
        AnnotationDocument doc = mapper.readValue(new File(flags.get("annotation")), AnnotationDocument.class);
        JsonNode taxpayerData = mapper.readTree(new File(flags.get("data")));

        System.out.println("Loaded annotation '" + doc.formId + "' (" + doc.fields.size() + " top-level fields).");

        // Step 2 of the pipeline: verify the source PDF matches what this annotation was authored against.
        byte[] pdfBytes = Files.readAllBytes(Path.of(flags.get("pdf")));
        String actualSha256 = sha256Hex(pdfBytes);
        java.util.List<Diagnostic> preflight = new java.util.ArrayList<>();
        if (doc.sourcePdf != null && doc.sourcePdf.sha256 != null) {
            if (!doc.sourcePdf.sha256.equalsIgnoreCase(actualSha256)) {
                preflight.add(Diagnostic.error("sourcePdf", "PDF_HASH_MISMATCH",
                        "Annotation expects SHA-256 " + doc.sourcePdf.sha256 + " but " + flags.get("pdf") +
                                " hashes to " + actualSha256 + ". Refusing to render against an unverified form revision."));
            }
        }

        try (PDDocument pdf = Loader.loadPDF(new File(flags.get("pdf")))) {
            if (doc.sourcePdf != null && doc.sourcePdf.pageCount > 0 && pdf.getNumberOfPages() != doc.sourcePdf.pageCount) {
                preflight.add(Diagnostic.error("sourcePdf", "PDF_PAGE_COUNT_MISMATCH",
                        "Annotation expects " + doc.sourcePdf.pageCount + " pages but PDF has " + pdf.getNumberOfPages() + "."));
            }

            // Plan stage: resolve + aggregate + format + decide overflow, across the whole document.
            FieldPlanner planner = new FieldPlanner();
            RenderPlan plan = planner.plan(doc.fields, taxpayerData);
            plan.diagnostics.addAll(0, preflight);

            System.out.println();
            System.out.println("=== Diagnostics (" + plan.diagnostics.size() + ") ===");
            if (plan.diagnostics.isEmpty()) {
                System.out.println("(none)");
            } else {
                for (Diagnostic d : plan.diagnostics) {
                    System.out.println(d);
                }
            }
            System.out.println();

            if (!preflight.isEmpty() || plan.hasErrors()) {
                System.err.println("Render ABORTED: one or more ERROR-level diagnostics were found. " +
                        "Fix the annotation or data and re-run. No output file was written.");
                System.exit(1);
            }

            System.out.println("Plan produced " + plan.draws.size() + " draw operations across " +
                    pdf.getNumberOfPages() + " page(s). Drawing...");

            PdfDrawer drawer = new PdfDrawer();
            drawer.draw(pdf, plan.draws, debug);

            Path outPath = Path.of(flags.get("out"));
            if (outPath.getParent() != null) {
                Files.createDirectories(outPath.getParent());
            }
            pdf.save(outPath.toFile());
            System.out.println("Wrote " + outPath.toAbsolutePath());
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> flags = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                String key = args[i].substring(2);
                if (key.equals("debug")) {
                    flags.put("debug", "true");
                    continue;
                }
                if (i + 1 < args.length) {
                    flags.put(key, args[++i]);
                }
            }
        }
        return flags;
    }

    private static void require(Map<String, String> flags, String key) {
        if (!flags.containsKey(key)) {
            System.err.println("Missing required flag: --" + key);
            System.err.println("Usage: java -jar annotation-renderer.jar --annotation <file> --data <file> --pdf <file> --out <file> [--debug]");
            System.exit(2);
        }
    }

    private static String sha256Hex(byte[] bytes) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
