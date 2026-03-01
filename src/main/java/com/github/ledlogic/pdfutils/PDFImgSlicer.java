package com.github.ledlogic.pdfutils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.util.Matrix;

/**
 * PDFImgSlicer - Slices a PDF into multiple output PDFs based on a width or height constraint.
 *
 * Usage:
 *   java PDFImgSlicer filename=<path-to-pdf> width=<inches>
 *   java PDFImgSlicer filename=<path-to-pdf> height=<inches>
 *   java PDFImgSlicer folder=<path-to-folder> width=<inches>
 *   java PDFImgSlicer folder=<path-to-folder> height=<inches>
 *
 * Output files are written to the same directory as each input PDF, named:
 *   <originalFilename>_w<xx>.pdf        (single slice, width mode)
 *   <originalFilename>_w<xx>_1.pdf, ... (multiple slices, width mode)
 *   <originalFilename>_h<yy>.pdf        (single slice, height mode)
 *   <originalFilename>_h<yy>_1.pdf, ... (multiple slices, height mode)
 *
 * When folder= is used, all .pdf files in that folder are processed.
 * Previously generated slice outputs (identified by the slice suffix in their
 * filename) are automatically skipped to avoid re-processing.
 *
 * Arguments may be supplied in any order.
 *
 * PDF units: 1 point = 1/72 inch.
 */
public class PDFImgSlicer {

    private static final float POINTS_PER_INCH = 72f;

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    public static void main(String[] args) {
        if (args.length < 2) {
            printUsage();
            System.exit(1);
        }

        // Parse named parameters - order does not matter
        String  filenameVal  = null;
        String  folderVal    = null;
        Boolean sliceByWidth = null;
        float   inches       = 0;

        for (String arg : args) {
            String lower = arg.toLowerCase();
            if (lower.startsWith("filename=")) {
                filenameVal = arg.substring("filename=".length());
            } else if (lower.startsWith("folder=")) {
                folderVal = arg.substring("folder=".length());
            } else if (lower.startsWith("width=")) {
                sliceByWidth = true;
                inches = parseInches(arg.substring("width=".length()));
            } else if (lower.startsWith("height=")) {
                sliceByWidth = false;
                inches = parseInches(arg.substring("height=".length()));
            } else {
                System.err.println("Warning: unrecognised argument ignored: " + arg);
            }
        }

        // Validate inputs
        if (filenameVal == null && folderVal == null) {
            System.err.println("Error: must supply filename=<file> or folder=<dir>");
            printUsage();
            System.exit(1);
        }
        if (filenameVal != null && folderVal != null) {
            System.err.println("Error: supply either filename= or folder=, not both");
            printUsage();
            System.exit(1);
        }
        if (sliceByWidth == null) {
            System.err.println("Error: must supply width=<inches> or height=<inches>");
            printUsage();
            System.exit(1);
        }
        if (inches <= 0) {
            System.err.println("Error: dimension must be a positive number.");
            System.exit(1);
        }

        PDFImgSlicer slicer = new PDFImgSlicer();

        if (filenameVal != null) {
            // ---- Single-file mode ----
            File pdfFile = new File(filenameVal);
            if (!pdfFile.exists() || !pdfFile.isFile()) {
                System.err.println("Error: PDF file not found: " + filenameVal);
                System.exit(1);
            }
            slicer.slice(pdfFile, sliceByWidth, inches);

        } else {
            // ---- Folder mode ----
            File folder = new File(folderVal);
            if (!folder.exists() || !folder.isDirectory()) {
                System.err.println("Error: folder not found: " + folderVal);
                System.exit(1);
            }

            // The suffix we are about to generate - used to skip previously produced slices
            final String sliceSuffix = sliceByWidth
                    ? "_w" + formatInches(inches)
                    : "_h" + formatInches(inches);

            File[] pdfs = folder.listFiles((dir, name) -> {
                if (!name.toLowerCase().endsWith(".pdf")) return false;
                // Skip files that are already slice outputs from a previous run
                if (name.contains(sliceSuffix)) {
                    System.out.println("Skipping previously generated slice: " + name);
                    return false;
                }
                return true;
            });

            if (pdfs == null || pdfs.length == 0) {
                System.out.println("No eligible PDF files found in folder: " + folderVal);
                System.exit(0);
            }

            System.out.printf("Found %d PDF file(s) to process in: %s%n", pdfs.length, folderVal);

            int success = 0;
            int failed  = 0;

            for (File pdf : pdfs) {
                System.out.println("\n" + "=".repeat(60));
                try {
                    slicer.slice(pdf, sliceByWidth, inches);
                    success++;
                } catch (Exception e) {
                    System.err.println("Failed to process " + pdf.getName() + ": " + e.getMessage());
                    e.printStackTrace();
                    failed++;
                }
            }

            System.out.println("\n" + "=".repeat(60));
            System.out.printf("Folder processing complete. Success: %d  Failed: %d%n", success, failed);
        }
    }

    // -------------------------------------------------------------------------
    // Core slicing logic
    // -------------------------------------------------------------------------

    /**
     * Slices every page of the PDF along the specified axis and writes one output
     * PDF per slice band (collected across all pages).
     *
     * @param pdfFile      source PDF
     * @param sliceByWidth true  -> slice along X axis (width constraint);
     *                     false -> slice along Y axis (height constraint)
     * @param inches       maximum width or height of each slice (in inches)
     */
    public void slice(File pdfFile, boolean sliceByWidth, float inches) {
        float  slicePts = inches * POINTS_PER_INCH;
        String suffix   = sliceByWidth ? ("_w" + formatInches(inches)) : ("_h" + formatInches(inches));

        System.out.println("Processing PDF : " + pdfFile.getName());
        System.out.printf("Slice mode     : %s = %.2f in (%.1f pt)%n",
                sliceByWidth ? "width" : "height", inches, slicePts);

        try (PDDocument srcDoc = PDDocument.load(pdfFile)) {
            int totalPages = srcDoc.getNumberOfPages();
            System.out.println("Total pages    : " + totalPages);

            // Build slice groups.
            // sliceGroups.get(i) = list of SliceSpecs (one per source page) for the i-th band.
            // Pages narrower/shorter than the slice size produce only one band each.
            List<List<SliceSpec>> sliceGroups = new ArrayList<>();

            for (int pageIdx = 0; pageIdx < totalPages; pageIdx++) {
                PDPage      page     = srcDoc.getPage(pageIdx);
                PDRectangle mediaBox = page.getMediaBox();

                float pageWidth  = mediaBox.getWidth();
                float pageHeight = mediaBox.getHeight();

                List<float[]> bands = sliceByWidth
                        ? computeBands(pageWidth,  slicePts)
                        : computeBands(pageHeight, slicePts);

                // Grow the groups list to accommodate this page's band count
                while (sliceGroups.size() < bands.size()) {
                    sliceGroups.add(new ArrayList<>());
                }

                for (int si = 0; si < bands.size(); si++) {
                    float[]     band = bands.get(si);
                    PDRectangle cropBox;

                    if (sliceByWidth) {
                        // band = [xStart, xEnd] measured from left edge
                        cropBox = new PDRectangle(
                                mediaBox.getLowerLeftX() + band[0],
                                mediaBox.getLowerLeftY(),
                                band[1] - band[0],
                                pageHeight);
                    } else {
                        // band = [distFromTop_start, distFromTop_end]
                        // Convert to PDF coordinate system (y=0 at bottom)
                        float yTop    = pageHeight - band[0];
                        float yBottom = pageHeight - band[1];
                        cropBox = new PDRectangle(
                                mediaBox.getLowerLeftX(),
                                mediaBox.getLowerLeftY() + yBottom,
                                pageWidth,
                                yTop - yBottom);
                    }

                    sliceGroups.get(si).add(new SliceSpec(pageIdx, cropBox));
                }
            }

            // Write one output PDF per slice group
            String parentDir = pdfFile.getParent() != null ? pdfFile.getParent() : ".";
            String baseName  = pdfFile.getName().replaceFirst("\\.[^.]+$", "");

            System.out.printf("Producing %d output PDF(s)...%n", sliceGroups.size());

            for (int gi = 0; gi < sliceGroups.size(); gi++) {
                List<SliceSpec> group = sliceGroups.get(gi);

                // No trailing number when the entire PDF fits in one slice
                String fileSuffix = (sliceGroups.size() == 1)
                        ? suffix + ".pdf"
                        : suffix + "_" + (gi + 1) + ".pdf";

                File outFile = new File(parentDir, baseName + fileSuffix);

                try (PDDocument outDoc = new PDDocument()) {
                    for (SliceSpec spec : group) {
                        PDPage      srcPage = srcDoc.getPage(spec.pageIndex);
                        PDRectangle crop    = spec.cropBox;

                        // New page sized exactly to the slice dimensions
                        PDPage newPage = new PDPage(new PDRectangle(crop.getWidth(), crop.getHeight()));
                        outDoc.addPage(newPage);

                        // Import source page as a Form XObject (preserves all vector/image content)
                        PDFormXObject formXObject = importPageAsForm(srcDoc, outDoc, srcPage);

                        try (PDPageContentStream cs = new PDPageContentStream(
                                outDoc, newPage, PDPageContentStream.AppendMode.APPEND, false)) {

                            // Translate so the desired crop region aligns to the new page origin
                            float tx = -(crop.getLowerLeftX() - srcPage.getMediaBox().getLowerLeftX());
                            float ty = -(crop.getLowerLeftY() - srcPage.getMediaBox().getLowerLeftY());

                            cs.saveGraphicsState();
                            cs.transform(Matrix.getTranslateInstance(tx, ty));
                            cs.drawForm(formXObject);
                            cs.restoreGraphicsState();
                        }
                    }

                    outDoc.save(outFile);
                    System.out.printf("  Wrote: %-40s  (%d page(s), %.2f x %.2f in)%n",
                            outFile.getName(),
                            group.size(),
                            group.get(0).cropBox.getWidth()  / POINTS_PER_INCH,
                            group.get(0).cropBox.getHeight() / POINTS_PER_INCH);
                }
            }

            System.out.println("Done.");

        } catch (IOException e) {
            System.err.println("Error processing PDF: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // -------------------------------------------------------------------------
    // Helper: import a source page as a Form XObject in the destination document
    // -------------------------------------------------------------------------

    /**
     * Imports {@code srcPage} from {@code srcDoc} into {@code destDoc} as a
     * {@link PDFormXObject} so it can be painted faithfully via
     * {@link PDPageContentStream#drawForm}.
     * Uses PDFBox's LayerUtility for a resource-safe, lossless import.
     */
    private PDFormXObject importPageAsForm(PDDocument srcDoc, PDDocument destDoc, PDPage srcPage)
            throws IOException {
        org.apache.pdfbox.multipdf.LayerUtility lu =
                new org.apache.pdfbox.multipdf.LayerUtility(destDoc);
        return lu.importPageAsForm(srcDoc, srcDoc.getPages().indexOf(srcPage));
    }

    // -------------------------------------------------------------------------
    // Band computation
    // -------------------------------------------------------------------------

    /**
     * Divides [0, totalPts] into contiguous bands each no wider than
     * {@code slicePts}. Returns a list of [start, end] pairs in points.
     * The final band may be narrower than {@code slicePts} when the total is
     * not an exact multiple.
     *
     * For height slicing the values represent distance from the top of the page
     * (natural reading order); the caller converts to PDF coordinate space.
     */
    private List<float[]> computeBands(float totalPts, float slicePts) {
        List<float[]> bands = new ArrayList<>();
        float pos = 0;
        while (pos < totalPts) {
            float end = Math.min(pos + slicePts, totalPts);
            bands.add(new float[]{pos, end});
            pos = end;
        }
        return bands;
    }

    // -------------------------------------------------------------------------
    // Misc helpers
    // -------------------------------------------------------------------------

    private static float parseInches(String s) {
        try {
            return Float.parseFloat(s.trim());
        } catch (NumberFormatException e) {
            System.err.println("Error: invalid number: \"" + s + "\"");
            System.exit(1);
            return 0; // unreachable
        }
    }

    /**
     * Formats an inch value as a compact string: whole numbers have no decimal
     * (e.g. 48 -> "48"), fractional values use one decimal place (e.g. 8.5 -> "8.5").
     */
    private static String formatInches(float inches) {
        if (inches == Math.floor(inches)) {
            return String.valueOf((int) inches);
        }
        return String.format("%.1f", inches);
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  java PDFImgSlicer filename=<path-to-pdf> width=<inches>");
        System.out.println("  java PDFImgSlicer filename=<path-to-pdf> height=<inches>");
        System.out.println("  java PDFImgSlicer folder=<path-to-folder> width=<inches>");
        System.out.println("  java PDFImgSlicer folder=<path-to-folder> height=<inches>");
        System.out.println();
        System.out.println("Arguments may be supplied in any order.");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java PDFImgSlicer filename=map.pdf width=48");
        System.out.println("  java PDFImgSlicer height=11 filename=brochure.pdf");
        System.out.println("  java PDFImgSlicer folder=/maps/dungeons width=48");
        System.out.println("  java PDFImgSlicer folder=./output height=8.5");
        System.out.println();
        System.out.println("Output files are written alongside each source PDF:");
        System.out.println("  map_w48.pdf                        (fits in one slice)");
        System.out.println("  map_w48_1.pdf  map_w48_2.pdf ...   (multiple slices)");
        System.out.println("  map_h8.5_1.pdf map_h8.5_2.pdf ...  (height mode)");
        System.out.println();
        System.out.println("In folder mode, files already containing the slice suffix");
        System.out.println("are skipped automatically to avoid re-processing outputs.");
    }

    // -------------------------------------------------------------------------
    // Inner class
    // -------------------------------------------------------------------------

    /** Links a source page index to the crop rectangle to extract from it. */
    private static class SliceSpec {
        final int         pageIndex;
        final PDRectangle cropBox;

        SliceSpec(int pageIndex, PDRectangle cropBox) {
            this.pageIndex = pageIndex;
            this.cropBox   = cropBox;
        }
    }
}