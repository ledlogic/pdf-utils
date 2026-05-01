package com.github.ledlogic.pdfutils;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/* Enhanced PDF Image Extractor - Extracts XObjects, Form XObjects, and Inline Images
 * Supports processing a single PDF file or all PDF files in a directory.
 * @see https://claude.ai/chat/5efe4691-e4b0-4d1d-a9e2-dbce8edf30ae
 */

public class PDFImgExtractor extends PDFGraphicsStreamEngine {

	private static final int MIN_DIMENSION = 150;
	private static final boolean DEBUG_MODE = true; // Set to false to reduce output
	private static final boolean RENDER_VECTOR_PAGES = true; // Render pages with vector graphics
	private static final int[] PAGES_TO_RENDER = { 26, 27 }; // Pages to render as full images (for vector graphics)
	private static final int RENDER_DPI = 300; // DPI for rendering vector pages

	private int imageCounter = 0;
	private int skippedDuplicateCount = 0; // cross-page dupes skipped within this PDF

	// Per-page dedup: cleared at the start of each page.
	// Prevents the same XObject being written twice when both processResources()
	// and the content-stream Do operator fire for the same image on one page.
	private final Set<String> currentPageChecksums = new HashSet<>();

	// Cross-page dedup: persists for the lifetime of this PDF.
	// Maps checksumKey -> ImageFingerprint of the first (and only) saved copy.
	// When a checksum is already present we skip writing entirely.
	private final Map<String, ImageFingerprint> imageFingerprints = new HashMap<>();

	// Furniture detection: tracks checksums of images that have been
	// encountered >= 3 times so their single saved copy can be deleted.
	private final Set<String> furnitureChecksums = new HashSet<>();

	private String outputDir;
	private String baseName;
	private int currentPageNum;
	private PDFRenderer pdfRenderer;

	// Inner class to store image fingerprint data
	private static class ImageFingerprint {
		String filePath;
		int width;
		int height;
		int occurrenceCount;

		ImageFingerprint(String filePath, int width, int height) {
			this.filePath = filePath;
			this.width = width;
			this.height = height;
			this.occurrenceCount = 1;
		}
	}

	public PDFImgExtractor(PDPage page) {
		super(page);
	}

	public static void main(String[] args) {
		if (args.length < 1) {
			System.out.println("Usage: java PDFImgExtractor <path-to-pdf-file-or-folder>");
			System.exit(1);
		}

		String inputPath = args[0];
		File inputFile = new File(inputPath);

		if (!inputFile.exists()) {
			System.err.println("Error: Path not found: " + inputPath);
			System.exit(1);
		}

		if (inputFile.isDirectory()) {
			processPDFFolder(inputFile);
		} else if (inputFile.isFile()) {
			if (!inputPath.toLowerCase().endsWith(".pdf")) {
				System.err.println("Warning: File does not have a .pdf extension: " + inputPath);
			}
			PDFImgExtractor extractor = new PDFImgExtractor(null);
			extractor.extractImages(inputFile);
		} else {
			System.err.println("Error: Path is neither a file nor a directory: " + inputPath);
			System.exit(1);
		}
	}

	/**
	 * Recursively collect all PDF files under the given directory into the list.
	 */
	private static void collectPDFsRecursively(File dir, List<File> result) {
		File[] entries = dir.listFiles();
		if (entries == null) return;
		for (File entry : entries) {
			if (entry.isDirectory()) {
				collectPDFsRecursively(entry, result);
			} else if (entry.isFile() && entry.getName().toLowerCase().endsWith(".pdf")) {
				result.add(entry);
			}
		}
	}

	/**
	 * Process all PDF files found in the given directory and all subdirectories.
	 * Files are sorted by full path alphabetically before processing.
	 * Each PDF gets its own fresh extractor (state resets per file).
	 */
	private static void processPDFFolder(File folder) {
		List<File> pdfFiles = new ArrayList<>();
		collectPDFsRecursively(folder, pdfFiles);

		if (pdfFiles.isEmpty()) {
			System.out.println("No PDF files found under: " + folder.getAbsolutePath());
			return;
		}

		// Sort by full path so files in the same subdirectory stay together
		pdfFiles.sort((a, b) -> a.getAbsolutePath().compareToIgnoreCase(b.getAbsolutePath()));

		System.out.println("=== Folder Mode (recursive) ===");
		System.out.println("Root directory: " + folder.getAbsolutePath());
		System.out.println("Found " + pdfFiles.size() + " PDF file(s) to process:");
		String rootPath = folder.getAbsolutePath();
		for (int i = 0; i < pdfFiles.size(); i++) {
			String relativePath = pdfFiles.get(i).getAbsolutePath();
			if (relativePath.startsWith(rootPath)) {
				relativePath = relativePath.substring(rootPath.length() + 1);
			}
			System.out.println("  " + (i + 1) + ". " + relativePath);
		}
		System.out.println();

		int successCount = 0;
		int failCount = 0;

		for (int i = 0; i < pdfFiles.size(); i++) {
			File pdfFile = pdfFiles.get(i);
			String relativePath = pdfFile.getAbsolutePath();
			if (relativePath.startsWith(rootPath)) {
				relativePath = relativePath.substring(rootPath.length() + 1);
			}
			System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
			System.out.println("Processing file " + (i + 1) + " of " + pdfFiles.size() + ": " + relativePath);
			System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
			try {
				PDFImgExtractor extractor = new PDFImgExtractor(null);
				extractor.extractImages(pdfFile);
				successCount++;
			} catch (Exception e) {
				System.err.println("Failed to process " + pdfFile.getName() + ": " + e.getMessage());
				failCount++;
			}
			System.out.println();
		}

		System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
		System.out.println("=== Folder Processing Complete ===");
		System.out.println("Successfully processed: " + successCount + " file(s)");
		if (failCount > 0) {
			System.out.println("Failed:                 " + failCount + " file(s)");
		}
		System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
	}

	public void extractImages(File pdfFile) {
		try (PDDocument document = PDDocument.load(pdfFile)) {
			this.pdfRenderer = new PDFRenderer(document);

			System.out.println("Processing PDF: " + pdfFile.getName());
			System.out.println("Total pages: " + document.getNumberOfPages());

			this.baseName = pdfFile.getName().replaceFirst("[.][^.]+$", "");

			String parentDir = pdfFile.getParent() != null ? pdfFile.getParent() : ".";
			File outputDirFile = new File(parentDir, this.baseName);
			if (!outputDirFile.exists() && !outputDirFile.mkdirs()) {
				System.err.println("Error: Could not create output directory: " + outputDirFile.getAbsolutePath());
				return;
			}
			this.outputDir = outputDirFile.getAbsolutePath();
			System.out.println("Output folder: " + this.outputDir);

			int totalPages = document.getNumberOfPages();

			for (int pageNum = 0; pageNum < totalPages; pageNum++) {
				this.currentPageNum = pageNum + 1;
				PDPage page = document.getPage(pageNum);
				extractFromPage(page);
			}

			System.out.println("\nExtraction complete!");
			System.out.println("Unique images extracted:       " + imageCounter);
			System.out.println("Cross-page duplicates skipped: " + skippedDuplicateCount);

			identifyAndRemoveFurniture();

		} catch (IOException e) {
			System.err.println("Error processing PDF: " + e.getMessage());
			e.printStackTrace();
		}
	}

	/**
	 * Furniture detection: images whose checksum was encountered >= 3 times are
	 * considered decorative repeats. Because duplicates are now skipped at write
	 * time, only one copy exists on disk per unique checksum — delete that file.
	 */
	private void identifyAndRemoveFurniture() {
		System.out.println("\n=== Furniture Detection ===");

		int furnitureCount = 0;

		for (Map.Entry<String, ImageFingerprint> entry : imageFingerprints.entrySet()) {
			if (entry.getValue().occurrenceCount >= 3) {
				furnitureChecksums.add(entry.getKey());
				ImageFingerprint fp = entry.getValue();
				System.out.println("  Furniture detected: " + fp.width + "x" + fp.height
						+ "px (encountered " + fp.occurrenceCount + " times)");
			}
		}

		for (String key : furnitureChecksums) {
			ImageFingerprint fp = imageFingerprints.get(key);
			if (fp != null) {
				File imageFile = new File(fp.filePath);
				if (imageFile.exists() && imageFile.delete()) {
					furnitureCount++;
					System.out.println("  ✗ Deleted furniture: " + imageFile.getName());
				}
			}
		}

		System.out.println("Unique furniture patterns:  " + furnitureChecksums.size());
		System.out.println("Furniture files deleted:    " + furnitureCount);
		System.out.println("Content images retained:    " + (imageCounter - furnitureCount));
	}

	private void extractFromPage(PDPage page) {
		try {
			System.out.println("\nProcessing page " + currentPageNum + "...");

			if (DEBUG_MODE) {
				System.out.println("  [DEBUG] currentPageNum = " + currentPageNum);
			}

			// Reset per-page tracking so the same XObject isn't double-counted
			// when both processResources() and the Do operator fire on the same page
			currentPageChecksums.clear();

			if (RENDER_VECTOR_PAGES && shouldRenderPage(currentPageNum)) {
				System.out.println("  [RENDER MODE] Rendering entire page as image (vector graphics)");
				renderPageAsImage(page);
			}

			PDResources resources = page.getResources();
			if (resources != null) {
				processResources(resources);
			}

			this.processPage(page);

		} catch (IOException e) {
			System.err.println("Error processing page " + currentPageNum + ": " + e.getMessage());
		}
	}

	private boolean shouldRenderPage(int pageNum) {
		for (int page : PAGES_TO_RENDER) {
			if (page == pageNum) {
				if (DEBUG_MODE) {
					System.out.println("    [CONFIG] Page " + pageNum + " matches PAGES_TO_RENDER");
				}
				return true;
			}
		}
		if (DEBUG_MODE && pageNum >= 115 && pageNum <= 120) {
			System.out.println("    [CONFIG] Page " + pageNum + " NOT in PAGES_TO_RENDER array: "
					+ Arrays.toString(PAGES_TO_RENDER));
		}
		return false;
	}

	private void renderPageAsImage(PDPage page) {
		try {
			int zeroBasedIndex = currentPageNum - 1;

			if (DEBUG_MODE) {
				System.out.println("    [RENDER] currentPageNum = " + currentPageNum + " (display number)");
				System.out.println("    [RENDER] Zero-based index = " + zeroBasedIndex + " (for PDFRenderer)");
				System.out.println("    [RENDER] Rendering at " + RENDER_DPI + " DPI");
			}

			float scale = RENDER_DPI / 72f;
			BufferedImage pageImage = pdfRenderer.renderImage(zeroBasedIndex, scale, ImageType.RGB);

			if (pageImage == null) {
				System.out.println("    [RENDER ERROR] Failed to render page - renderImage() returned null");
				return;
			}

			int width = pageImage.getWidth();
			int height = pageImage.getHeight();

			if (DEBUG_MODE) {
				System.out.println("    [RENDER] Successfully rendered: " + width + "x" + height + " pixels");
			}

			imageCounter++;
			String fileName = String.format("%s_page%d_rendered_%dx%d.png", baseName, currentPageNum, width, height);
			String outputPath = outputDir + File.separator + fileName;

			ImageIO.write(pageImage, "PNG", new File(outputPath));
			System.out.println("  ✓ Rendered page: " + fileName + " (" + width + "x" + height + "px) [VECTOR]");

		} catch (IOException e) {
			System.err.println("    [RENDER ERROR] " + e.getMessage());
			if (DEBUG_MODE) {
				e.printStackTrace();
			}
		}
	}

	// Implement required abstract methods from PDFGraphicsStreamEngine
	@Override
	public void appendRectangle(java.awt.geom.Point2D p0, java.awt.geom.Point2D p1,
			java.awt.geom.Point2D p2, java.awt.geom.Point2D p3) {}

	@Override
	public void drawImage(PDImage pdImage) throws IOException {
		if (pdImage instanceof PDImageXObject) {
			processImage((PDImageXObject) pdImage, "inline_" + imageCounter);
		}
	}

	@Override public void clip(int windingRule) {}
	@Override public void moveTo(float x, float y) {}
	@Override public void lineTo(float x, float y) {}
	@Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {}
	@Override public java.awt.geom.Point2D getCurrentPoint() { return new java.awt.geom.Point2D.Float(0, 0); }
	@Override public void closePath() {}
	@Override public void endPath() {}
	@Override public void strokePath() {}
	@Override public void fillPath(int windingRule) {}
	@Override public void fillAndStrokePath(int windingRule) {}
	@Override public void shadingFill(COSName shadingName) {}

	private void processResources(PDResources resources) throws IOException {
		for (COSName name : resources.getXObjectNames()) {
			PDXObject xObject = resources.getXObject(name);
			if (xObject instanceof PDImageXObject) {
				processImage((PDImageXObject) xObject, name.getName());
			} else if (xObject instanceof PDFormXObject) {
				processFormXObject((PDFormXObject) xObject);
			}
		}
	}

	private void processFormXObject(PDFormXObject form) throws IOException {
		PDResources formResources = form.getResources();
		if (formResources != null) {
			for (COSName name : formResources.getXObjectNames()) {
				PDXObject xObject = formResources.getXObject(name);
				if (xObject instanceof PDImageXObject) {
					processImage((PDImageXObject) xObject, name.getName());
				} else if (xObject instanceof PDFormXObject) {
					processFormXObject((PDFormXObject) xObject);
				}
			}
		}
	}

	private void processImage(PDImageXObject image, String imageName) {
		try {
			int width = image.getWidth();
			int height = image.getHeight();

			// --- Filter: too small ---
			if (width < MIN_DIMENSION || height < MIN_DIMENSION) {
				if (DEBUG_MODE) {
					System.out.println("  [SKIP: TOO SMALL] " + imageName + " (" + width + "x" + height
							+ "px) - min: " + MIN_DIMENSION + "px");
				}
				return;
			}

			BufferedImage bImage = image.getImage();
			if (bImage == null) {
				System.out.println("  Could not render image: " + imageName);
				return;
			}

			long checksum = calculateImageChecksum(bImage);
			String checksumKey = width + "x" + height + "_" + checksum;

			// --- Filter: already seen on this page ---
			// (guards against the same XObject firing via both processResources
			//  and the content-stream Do operator on the same page)
			if (currentPageChecksums.contains(checksumKey)) {
				if (DEBUG_MODE) {
					System.out.println("  [SKIP: DUPLICATE ON PAGE] " + imageName
							+ " (" + width + "x" + height + "px)");
				}
				return;
			}
			currentPageChecksums.add(checksumKey);

			// --- Filter: already seen on a previous page of this PDF ---
			// Skip writing entirely; just bump the occurrence count for furniture detection.
			if (imageFingerprints.containsKey(checksumKey)) {
				ImageFingerprint existing = imageFingerprints.get(checksumKey);
				existing.occurrenceCount++;
				skippedDuplicateCount++;
				System.out.println("  [SKIP: DUPLICATE ACROSS PAGES] " + imageName
						+ " (" + width + "x" + height + "px)"
						+ " - first seen on page " + extractPageNumFromPath(existing.filePath)
						+ " as " + new File(existing.filePath).getName()
						+ " (occurrence #" + existing.occurrenceCount + ")");
				return;
			}

			// --- Filter: grayscale / black-and-white ---
			if (!isColoredImage(bImage)) {
				if (DEBUG_MODE) {
					System.out.println("  [SKIP: GRAYSCALE/B&W] " + imageName
							+ " (" + width + "x" + height + "px)");
				}
				return;
			}

			// --- New unique image — write it and register its checksum ---
			imageCounter++;
			String fileName = String.format("%s_page%d_img%d_%dx%d.png",
					baseName, currentPageNum, imageCounter, width, height);
			String outputPath = outputDir + File.separator + fileName;

			ImageIO.write(bImage, "PNG", new File(outputPath));
			System.out.println("  ✓ Extracted: " + fileName + " (" + width + "x" + height + "px) [COLOR]");

			imageFingerprints.put(checksumKey, new ImageFingerprint(outputPath, width, height));

		} catch (IOException e) {
			System.err.println("  Error extracting image " + imageName + ": " + e.getMessage());
		}
	}

	/**
	 * Pull the page number out of a filename like "myPdf_page12_img3_800x600.png"
	 * for a cleaner duplicate-skip log message.
	 */
	private static int extractPageNumFromPath(String filePath) {
		try {
			String name = new File(filePath).getName();
			int pageIdx = name.indexOf("_page");
			if (pageIdx >= 0) {
				int start = pageIdx + 5;
				int end = name.indexOf('_', start);
				if (end > start) {
					return Integer.parseInt(name.substring(start, end));
				}
			}
		} catch (NumberFormatException ignored) {
		}
		return -1;
	}

	/**
	 * Determines if an image has color (not grayscale or black-and-white).
	 * Samples pixels to check for significant color saturation.
	 */
	private boolean isColoredImage(BufferedImage image) {
		int width = image.getWidth();
		int height = image.getHeight();

		int imageType = image.getType();
		boolean isGrayscaleType = (imageType == BufferedImage.TYPE_BYTE_GRAY
				|| imageType == BufferedImage.TYPE_USHORT_GRAY);

		if (DEBUG_MODE) {
			System.out.println("    [COLOR CHECK] Image type: " + getImageTypeName(imageType));
		}

		if (isGrayscaleType) {
			if (DEBUG_MODE) {
				System.out.println("    [COLOR CHECK] Grayscale type detected - accepting as map/diagram");
			}
			return true;
		}

		int stepX = Math.max(1, width / 20);
		int stepY = Math.max(1, height / 20);

		int coloredPixels = 0;
		int totalSamples = 0;

		for (int y = 0; y < height; y += stepY) {
			for (int x = 0; x < width; x += stepX) {
				int rgb = image.getRGB(x, y);
				int red   = (rgb >> 16) & 0xFF;
				int green = (rgb >>  8) & 0xFF;
				int blue  =  rgb        & 0xFF;

				if (red > 240 && green > 240 && blue > 240) { totalSamples++; continue; }
				if (red <  15 && green <  15 && blue <  15) { totalSamples++; continue; }

				int maxDiff = Math.max(Math.abs(red - green),
						Math.max(Math.abs(red - blue), Math.abs(green - blue)));
				if (maxDiff > 10) coloredPixels++;
				totalSamples++;
			}
		}

		double colorRatio = (double) coloredPixels / totalSamples;

		if (DEBUG_MODE && colorRatio > 0) {
			System.out.println("    [COLOR CHECK] "
					+ String.format("%.1f%% colored pixels (threshold: 1.0%%)", colorRatio * 100));
		}

		return colorRatio > 0.01;
	}

	private String getImageTypeName(int type) {
		switch (type) {
		case BufferedImage.TYPE_INT_RGB:      return "RGB";
		case BufferedImage.TYPE_INT_ARGB:     return "ARGB";
		case BufferedImage.TYPE_INT_BGR:      return "BGR";
		case BufferedImage.TYPE_3BYTE_BGR:    return "3BYTE_BGR";
		case BufferedImage.TYPE_4BYTE_ABGR:   return "4BYTE_ABGR";
		case BufferedImage.TYPE_BYTE_GRAY:    return "GRAYSCALE (8-bit)";
		case BufferedImage.TYPE_USHORT_GRAY:  return "GRAYSCALE (16-bit)";
		case BufferedImage.TYPE_BYTE_BINARY:  return "BINARY";
		case BufferedImage.TYPE_BYTE_INDEXED: return "INDEXED";
		default: return "CUSTOM (" + type + ")";
		}
	}

	/**
	 * Calculate a checksum for an image to detect identical content.
	 * Uses a sampling approach for performance on large images.
	 */
	private long calculateImageChecksum(BufferedImage image) {
		int width = image.getWidth();
		int height = image.getHeight();
		long checksum = 0;

		int stepX = Math.max(1, width / 20);
		int stepY = Math.max(1, height / 20);

		for (int y = 0; y < height; y += stepY) {
			for (int x = 0; x < width; x += stepX) {
				checksum = checksum * 31 + image.getRGB(x, y);
			}
		}

		checksum = checksum * 31 + width;
		checksum = checksum * 31 + height;
		return checksum;
	}
}