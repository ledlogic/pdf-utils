package com.github.ledlogic.pdfutils;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
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
 * @see https://claude.ai/chat/5efe4691-e4b0-4d1d-a9e2-dbce8edf30ae 
 */

public class PDFImgExtractor extends PDFGraphicsStreamEngine {

	private static final int MIN_DIMENSION = 150;
	private static final boolean DEBUG_MODE = true; // Set to false to reduce output
	private static final boolean RENDER_VECTOR_PAGES = true; // Render pages with vector graphics
	private static final int[] PAGES_TO_RENDER = { 26, 27 }; // Pages to render as full images (for vector graphics)
	private static final int RENDER_DPI = 300; // DPI for rendering vector pages
	private int imageCounter = 0;
	private Map<String, ImageFingerprint> imageFingerprints = new HashMap<>();
	private Set<String> furnitureImages = new HashSet<>();
	private Set<String> currentPageChecksums = new HashSet<>(); // Track checksums within current page
	private String outputDir;
	private String baseName;
	private int currentPageNum;
	private PDFRenderer pdfRenderer; // Renderer for vector graphics

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
			System.out.println("Usage: java PDFImageExtractor <path-to-pdf-file>");
			System.exit(1);
		}

		String pdfPath = args[0];
		File pdfFile = new File(pdfPath);

		if (!pdfFile.exists()) {
			System.err.println("Error: PDF file not found: " + pdfPath);
			System.exit(1);
		}

		PDFImgExtractor extractor = new PDFImgExtractor(null);
		extractor.extractImages(pdfFile);
	}

	public void extractImages(File pdfFile) {
		try (PDDocument document = PDDocument.load(pdfFile)) {
			this.pdfRenderer = new PDFRenderer(document); // Initialize renderer

			System.out.println("Processing PDF: " + pdfFile.getName());
			System.out.println("Total pages: " + document.getNumberOfPages());

			this.baseName = pdfFile.getName().replaceFirst("[.][^.]+$", "");

			// Output images to a subfolder named after the PDF (minus extension)
			// to avoid filename collisions when processing multiple PDFs in the same
			// directory.
			String parentDir = pdfFile.getParent() != null ? pdfFile.getParent() : ".";
			File outputDirFile = new File(parentDir, this.baseName);
			if (!outputDirFile.exists() && !outputDirFile.mkdirs()) {
				System.err.println("Error: Could not create output directory: " + outputDirFile.getAbsolutePath());
				return;
			}
			this.outputDir = outputDirFile.getAbsolutePath();
			System.out.println("Output folder: " + this.outputDir);

			int totalPages = document.getNumberOfPages();
			// CHANGED: Process ALL pages instead of skipping last 4
			int pagesToProcess = totalPages;

			for (int pageNum = 0; pageNum < pagesToProcess; pageNum++) {
				this.currentPageNum = pageNum + 1;
				PDPage page = document.getPage(pageNum);
				extractFromPage(page);
			}

			System.out.println("\nExtraction complete!");
			System.out.println("Total images extracted: " + imageCounter);

			// Identify and remove furniture images (decorative duplicates)
			identifyAndRemoveFurniture();

		} catch (IOException e) {
			System.err.println("Error processing PDF: " + e.getMessage());
			e.printStackTrace();
		}
	}

	private void identifyAndRemoveFurniture() {
		System.out.println("\n=== Furniture Detection ===");
		System.out.println("Analyzing images for decorative duplicates...");

		int furnitureCount = 0;

		// Find images that appear multiple times (furniture/decorative elements)
		for (Map.Entry<String, ImageFingerprint> entry : imageFingerprints.entrySet()) {
			ImageFingerprint fp = entry.getValue();

			// If an image appears 3+ times, it's likely furniture/decoration
			if (fp.occurrenceCount >= 3) {
				furnitureImages.add(entry.getKey());
				System.out.println("  Furniture detected: " + fp.width + "x" + fp.height + "px (appeared "
						+ fp.occurrenceCount + " times)");
			}
		}

		// Delete all copies of furniture images
		for (Map.Entry<String, ImageFingerprint> entry : imageFingerprints.entrySet()) {
			if (furnitureImages.contains(entry.getKey())) {
				ImageFingerprint fp = entry.getValue();
				File imageFile = new File(fp.filePath);
				if (imageFile.exists() && imageFile.delete()) {
					furnitureCount++;
					System.out.println("  ✗ Deleted furniture: " + imageFile.getName());
				}
			}
		}

		System.out.println("\nFurniture cleanup complete!");
		System.out.println("Unique furniture patterns detected: " + furnitureImages.size());
		System.out.println("Total furniture images deleted: " + furnitureCount);
		System.out.println("Content images retained: " + (imageCounter - furnitureCount));
	}

	private void extractFromPage(PDPage page) {
		try {
			System.out.println("\nProcessing page " + currentPageNum + "...");

			if (DEBUG_MODE) {
				System.out.println("  [DEBUG] currentPageNum = " + currentPageNum);
			}

			// Clear per-page duplicate tracking
			currentPageChecksums.clear();

			// Check if this page should be rendered as a full image (for vector graphics)
			if (RENDER_VECTOR_PAGES && shouldRenderPage(currentPageNum)) {
				System.out.println("  [RENDER MODE] Rendering entire page as image (vector graphics)");
				renderPageAsImage(page);
			}

			// Extract XObject images (standard embedded images)
			PDResources resources = page.getResources();
			if (resources != null) {
				processResources(resources);
			}

			// Process content stream to catch inline images and Do operators
			this.processPage(page);

		} catch (IOException e) {
			System.err.println("Error processing page " + currentPageNum + ": " + e.getMessage());
		}
	}

	/**
	 * Check if a page should be rendered as a full image
	 */
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
					+ java.util.Arrays.toString(PAGES_TO_RENDER));
		}
		return false;
	}

	/**
	 * Render an entire page as a PNG image to capture vector graphics
	 */
	private void renderPageAsImage(PDPage page) {
		try {
			int zeroBasedIndex = currentPageNum - 1;

			if (DEBUG_MODE) {
				System.out.println("    [RENDER] currentPageNum = " + currentPageNum + " (display number)");
				System.out.println("    [RENDER] Zero-based index = " + zeroBasedIndex + " (for PDFRenderer)");
				System.out.println("    [RENDER] Rendering at " + RENDER_DPI + " DPI");
			}

			// Calculate scale factor from DPI (default is 72 DPI)
			float scale = RENDER_DPI / 72f;

			// Render the page (pageNum is zero-based for renderer)
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

			// Save the rendered page
			imageCounter++;
			String fileName = String.format("%s_page%d_rendered_%dx%d.png", baseName, currentPageNum, width, height);
			String outputPath = outputDir + File.separator + fileName;
			File outputFile = new File(outputPath);

			ImageIO.write(pageImage, "PNG", outputFile);
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
	public void appendRectangle(java.awt.geom.Point2D p0, java.awt.geom.Point2D p1, java.awt.geom.Point2D p2,
			java.awt.geom.Point2D p3) {
		// Not needed for image extraction
	}

	@Override
	public void drawImage(PDImage pdImage) throws IOException {
		// This gets called when an image is drawn via Do operator
		if (pdImage instanceof PDImageXObject) {
			processImage((PDImageXObject) pdImage, "inline_" + imageCounter);
		}
	}

	@Override
	public void clip(int windingRule) {
		// Not needed for image extraction
	}

	@Override
	public void moveTo(float x, float y) {
		// Not needed for image extraction
	}

	@Override
	public void lineTo(float x, float y) {
		// Not needed for image extraction
	}

	@Override
	public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
		// Not needed for image extraction
	}

	@Override
	public java.awt.geom.Point2D getCurrentPoint() {
		// Not needed for image extraction
		return new java.awt.geom.Point2D.Float(0, 0);
	}

	@Override
	public void closePath() {
		// Not needed for image extraction
	}

	@Override
	public void endPath() {
		// Not needed for image extraction
	}

	@Override
	public void strokePath() {
		// Not needed for image extraction
	}

	@Override
	public void fillPath(int windingRule) {
		// Not needed for image extraction
	}

	@Override
	public void fillAndStrokePath(int windingRule) {
		// Not needed for image extraction
	}

	@Override
	public void shadingFill(org.apache.pdfbox.cos.COSName shadingName) {
		// Not needed for image extraction
	}

	private void processResources(PDResources resources) throws IOException {
		// Process all XObject images
		for (COSName name : resources.getXObjectNames()) {
			PDXObject xObject = resources.getXObject(name);

			if (xObject instanceof PDImageXObject) {
				processImage((PDImageXObject) xObject, name.getName());
			} else if (xObject instanceof PDFormXObject) {
				// Form XObjects can contain nested images
				processFormXObject((PDFormXObject) xObject);
			}
		}
	}

	private void processFormXObject(PDFormXObject form) throws IOException {
		// Form XObjects can contain images - recursively process their resources
		PDResources formResources = form.getResources();
		if (formResources != null) {
			for (COSName name : formResources.getXObjectNames()) {
				PDXObject xObject = formResources.getXObject(name);

				if (xObject instanceof PDImageXObject) {
					processImage((PDImageXObject) xObject, name.getName());
				} else if (xObject instanceof PDFormXObject) {
					// Recursively process nested form XObjects
					processFormXObject((PDFormXObject) xObject);
				}
			}
		}
	}

	private void processImage(PDImageXObject image, String imageName) {
		try {
			int width = image.getWidth();
			int height = image.getHeight();

			// Filter: both dimensions must be >= MIN_DIMENSION
			if (width < MIN_DIMENSION || height < MIN_DIMENSION) {
				if (DEBUG_MODE) {
					System.out.println("  [SKIP: TOO SMALL] " + imageName + " (" + width + "x" + height + "px) - min: "
							+ MIN_DIMENSION + "px");
				}
				return;
			}

			BufferedImage bImage = image.getImage();
			if (bImage == null) {
				System.out.println("  Could not render image: " + imageName);
				return;
			}

			// Calculate image checksum for duplicate detection
			long checksum = calculateImageChecksum(bImage);
			String checksumKey = width + "x" + height + "_" + checksum;

			// Check if we've already saved this exact image on this page
			if (currentPageChecksums.contains(checksumKey)) {
				if (DEBUG_MODE) {
					System.out
							.println("  [SKIP: DUPLICATE ON PAGE] " + imageName + " (" + width + "x" + height + "px)");
				}
				return;
			}

			// Mark this image as seen on current page
			currentPageChecksums.add(checksumKey);

			// Filter: only keep colored images (not grayscale/black-and-white)
			if (!isColoredImage(bImage)) {
				if (DEBUG_MODE) {
					System.out.println("  [SKIP: GRAYSCALE/B&W] " + imageName + " (" + width + "x" + height + "px)");
				}
				return;
			}

			// Track for furniture detection across all pages
			String fingerprintKey = width + "x" + height + "_" + checksum;

			// Check if we've seen this exact image before (across all pages)
			if (imageFingerprints.containsKey(fingerprintKey)) {
				// Increment occurrence count for furniture detection
				imageFingerprints.get(fingerprintKey).occurrenceCount++;
				System.out.println("  Duplicate detected: " + imageName + " (" + width + "x" + height
						+ "px) - occurrence #" + imageFingerprints.get(fingerprintKey).occurrenceCount);
				// Still extract it - we'll delete furniture images at the end
			}

			imageCounter++;
			String fileName = String.format("%s_page%d_img%d_%dx%d.png", baseName, currentPageNum, imageCounter, width,
					height);
			String outputPath = outputDir + File.separator + fileName;
			File outputFile = new File(outputPath);

			ImageIO.write(bImage, "PNG", outputFile);
			System.out.println("  ✓ Extracted: " + fileName + " (" + width + "x" + height + "px) [COLOR]");

			// Store fingerprint for furniture detection
			if (!imageFingerprints.containsKey(fingerprintKey)) {
				imageFingerprints.put(fingerprintKey, new ImageFingerprint(outputPath, width, height));
			}

		} catch (IOException e) {
			System.err.println("  Error extracting image " + imageName + ": " + e.getMessage());
		}
	}

	/**
	 * Determines if an image has color (not grayscale or black-and-white). Samples
	 * pixels to check if there's significant color saturation.
	 */
	private boolean isColoredImage(BufferedImage image) {
		int width = image.getWidth();
		int height = image.getHeight();

		// Check the color model type
		int imageType = image.getType();
		boolean isGrayscaleType = (imageType == BufferedImage.TYPE_BYTE_GRAY
				|| imageType == BufferedImage.TYPE_USHORT_GRAY);

		if (DEBUG_MODE) {
			System.out.println("    [COLOR CHECK] Image type: " + getImageTypeName(imageType));
		}

		// If the image is stored as grayscale type, check if it has a blue tint
		// Some PDFs store blue/white images as grayscale with color mapping
		if (isGrayscaleType) {
			if (DEBUG_MODE) {
				System.out.println("    [COLOR CHECK] Grayscale type detected - accepting as map/diagram");
			}
			// Accept grayscale images - they might be blue/white maps
			// The PDF renderer may have already converted them
			return true;
		}

		// Sample pixels to check for color
		int stepX = Math.max(1, width / 20); // Sample every ~5% horizontally
		int stepY = Math.max(1, height / 20); // Sample every ~5% vertically

		int coloredPixels = 0;
		int totalSamples = 0;

		for (int y = 0; y < height; y += stepY) {
			for (int x = 0; x < width; x += stepX) {
				int rgb = image.getRGB(x, y);

				// Extract RGB components
				int red = (rgb >> 16) & 0xFF;
				int green = (rgb >> 8) & 0xFF;
				int blue = rgb & 0xFF;

				// Skip pure white and near-white pixels
				if (red > 240 && green > 240 && blue > 240) {
					totalSamples++;
					continue;
				}

				// Skip pure black and near-black pixels
				if (red < 15 && green < 15 && blue < 15) {
					totalSamples++;
					continue;
				}

				// Check if pixel has color (RGB values differ)
				// More lenient threshold: 10 instead of 15
				int maxDiff = Math.max(Math.abs(red - green), Math.max(Math.abs(red - blue), Math.abs(green - blue)));

				// If any color channel differs by more than 10, it's colored
				if (maxDiff > 10) {
					coloredPixels++;
				}

				totalSamples++;
			}
		}

		// More lenient threshold: 1% instead of 5%
		// This catches blue/white maps where most of image is white background
		double colorRatio = (double) coloredPixels / totalSamples;

		if (DEBUG_MODE && colorRatio > 0) {
			System.out.println(
					"    [COLOR CHECK] " + String.format("%.1f%% colored pixels (threshold: 1.0%%)", colorRatio * 100));
		}

		return colorRatio > 0.01;
	}

	private String getImageTypeName(int type) {
		switch (type) {
		case BufferedImage.TYPE_INT_RGB:
			return "RGB";
		case BufferedImage.TYPE_INT_ARGB:
			return "ARGB";
		case BufferedImage.TYPE_INT_BGR:
			return "BGR";
		case BufferedImage.TYPE_3BYTE_BGR:
			return "3BYTE_BGR";
		case BufferedImage.TYPE_4BYTE_ABGR:
			return "4BYTE_ABGR";
		case BufferedImage.TYPE_BYTE_GRAY:
			return "GRAYSCALE (8-bit)";
		case BufferedImage.TYPE_USHORT_GRAY:
			return "GRAYSCALE (16-bit)";
		case BufferedImage.TYPE_BYTE_BINARY:
			return "BINARY";
		case BufferedImage.TYPE_BYTE_INDEXED:
			return "INDEXED";
		default:
			return "CUSTOM (" + type + ")";
		}
	}

	/**
	 * Calculate a simple checksum for an image to detect identical content. Uses a
	 * sampling approach for performance on large images.
	 */
	private long calculateImageChecksum(BufferedImage image) {
		int width = image.getWidth();
		int height = image.getHeight();
		long checksum = 0;

		// Sample pixels in a grid pattern for performance
		int stepX = Math.max(1, width / 20); // Sample every ~5% horizontally
		int stepY = Math.max(1, height / 20); // Sample every ~5% vertically

		for (int y = 0; y < height; y += stepY) {
			for (int x = 0; x < width; x += stepX) {
				int rgb = image.getRGB(x, y);
				checksum = checksum * 31 + rgb; // Simple hash function
			}
		}

		// Include dimensions in checksum
		checksum = checksum * 31 + width;
		checksum = checksum * 31 + height;

		return checksum;
	}
}