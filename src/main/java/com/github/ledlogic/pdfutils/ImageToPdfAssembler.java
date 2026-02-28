package com.github.ledlogic.pdfutils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/**
 * ImageToPdfAssembler - Assembles PNG images into a portrait letter-sized PDF
 *
 * This tool scans a directory for all PNG files and creates a PDF with one
 * image per page, ordered by natural sort (so page7 comes before page12).
 *
 * Images are automatically rotated 90° if doing so reduces whitespace (i.e.,
 * a landscape image is rotated to fill a portrait page more efficiently, and
 * vice versa).
 *
 * Usage: java ImageToPdfAssembler <input_directory> <output_pdf>
 * Example: java ImageToPdfAssembler /path/to/images output.pdf
 */
public class ImageToPdfAssembler {

	// Letter size in points (1 inch = 72 points)
	private static final PDRectangle LETTER = PDRectangle.LETTER; // 8.5" x 11"

	// Pattern to match any PNG file
	private static final Pattern FILE_PATTERN = Pattern.compile("^.+\\.png$", Pattern.CASE_INSENSITIVE);

	public static void main(String[] args) {
		if (args.length != 2) {
			System.err.println("Usage: java ImageToPdfAssembler <input_directory> <output_pdf>");
			System.err.println("Example: java ImageToPdfAssembler /path/to/images output.pdf");
			System.exit(1);
		}

		String inputDirectory = args[0];
		String outputPdf = args[0] + File.separator + args[1];

		try {
			List<ImageFile> imageFiles = findAndSortImages(inputDirectory);

			if (imageFiles.isEmpty()) {
				System.err.println("No PNG files found in directory: " + inputDirectory);
				System.exit(1);
			}

			System.out.println("Found " + imageFiles.size() + " image(s) to process:");
			for (ImageFile imgFile : imageFiles) {
				System.out.println("  " + imgFile.getFile().getName());
			}

			createPdf(imageFiles, outputPdf);

			System.out.println("\nPDF successfully created: " + outputPdf);

		} catch (IOException e) {
			System.err.println("Error: " + e.getMessage());
			e.printStackTrace();
			System.exit(1);
		}
	}

	/**
	 * Find all PNG files and sort them using natural sort order,
	 * so that e.g. page7 comes before page12.
	 */
	private static List<ImageFile> findAndSortImages(String directoryPath) throws IOException {
		Path dir = Paths.get(directoryPath);

		if (!Files.exists(dir) || !Files.isDirectory(dir)) {
			throw new IOException("Directory does not exist or is not a directory: " + directoryPath);
		}

		List<ImageFile> imageFiles = Files.list(dir)
				.filter(Files::isRegularFile)
				.map(Path::toFile)
				.filter(file -> FILE_PATTERN.matcher(file.getName()).matches())
				.map(ImageFile::new)
				.collect(Collectors.toList());

		imageFiles.sort((a, b) -> naturalCompare(a.getFile().getName(), b.getFile().getName()));
		return imageFiles;
	}

	/**
	 * Natural sort comparator: splits filenames into text and numeric segments,
	 * comparing numeric segments as integers so "page7" < "page12".
	 */
	private static int naturalCompare(String a, String b) {
		String[] partsA = a.split("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)");
		String[] partsB = b.split("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)");
		for (int i = 0; i < Math.min(partsA.length, partsB.length); i++) {
			int cmp = partsA[i].matches("\\d+") && partsB[i].matches("\\d+")
				? Integer.compare(Integer.parseInt(partsA[i]), Integer.parseInt(partsB[i]))
				: partsA[i].compareTo(partsB[i]);
			if (cmp != 0) return cmp;
		}
		return Integer.compare(partsA.length, partsB.length);
	}

	/**
	 * Compute the scale factor that fits (imgW x imgH) into (pageW x pageH).
	 */
	private static float fitScale(float imgW, float imgH, float pageW, float pageH) {
		return Math.min(pageW / imgW, pageH / imgH);
	}

	/**
	 * Compute the area covered when fitting (imgW x imgH) into (pageW x pageH).
	 * Larger area = less whitespace.
	 */
	private static float coveredArea(float imgW, float imgH, float pageW, float pageH) {
		float scale = fitScale(imgW, imgH, pageW, pageH);
		return (imgW * scale) * (imgH * scale);
	}

	/**
	 * Create a PDF with one image per page.
	 * Each image is rotated 90° if that orientation covers more of the page area.
	 */
	private static void createPdf(List<ImageFile> imageFiles, String outputPdfPath) throws IOException {
		try (PDDocument document = new PDDocument()) {

			float pageWidth  = LETTER.getWidth();   // 612 pt (8.5")
			float pageHeight = LETTER.getHeight();  // 792 pt (11")

			for (ImageFile imageFile : imageFiles) {
				File file = imageFile.getFile();
				System.out.println("Processing: " + file.getName());

				PDImageXObject image = PDImageXObject.createFromFile(file.getAbsolutePath(), document);

				float imgW = image.getWidth();
				float imgH = image.getHeight();

				// Determine whether rotating 90° fills more of the page
				float areaNormal  = coveredArea(imgW, imgH, pageWidth, pageHeight);
				float areaRotated = coveredArea(imgH, imgW, pageWidth, pageHeight);
				boolean rotate = areaRotated > areaNormal;

				// Effective image dimensions after optional rotation
				float effectiveW = rotate ? imgH : imgW;
				float effectiveH = rotate ? imgW : imgH;

				float scale        = fitScale(effectiveW, effectiveH, pageWidth, pageHeight);
				float scaledWidth  = effectiveW * scale;
				float scaledHeight = effectiveH * scale;

				// Center the (possibly rotated) image on the page
				float x = (pageWidth  - scaledWidth)  / 2;
				float y = (pageHeight - scaledHeight) / 2;

				PDPage page = new PDPage(LETTER);
				document.addPage(page);

				try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
					if (rotate) {
						// To rotate the image 90° counter-clockwise and keep it centered:
						// We use PDFBox's transform: translate to center, rotate, translate back.
						// PDFBox drawImage draws from bottom-left, so we need to account for that.
						//
						// After a 90° CCW rotation the image's width becomes the page height
						// dimension and vice versa. We apply an affine transform:
						//   1. Translate to the bottom-left of where we want the rotated image
						//   2. Rotate 90° CCW
						//   3. Draw the original (unrotated) image starting at (0,0)
						//
						// Affine: [a b c d e f] where:
						//   a=cos(θ), b=sin(θ), c=-sin(θ), d=cos(θ), e=tx, f=ty
						// For 90° CCW: cos=0, sin=1  → a=0, b=1, c=-1, d=0
						// We want the image drawn so its rotated bounding box is at (x, y).
						// After rotation a point (px, py) maps to (-py, px).
						// The image corner (0,0) maps to (0,0) in rotated space.
						// The image corner (imgW, 0) maps to (0, imgW*scale).
						// So origin of the transformed image ends up at bottom-left of the
						// rotated bounding box, which should be at (x, y + scaledHeight).
						//
						// tx = x + scaledHeight  (because rotated width = scaledHeight = imgH*scale)
						//   Wait — after 90°CCW, image width (imgW) maps to page-height direction,
						//   image height (imgH) maps to page-width direction.
						//   scaledWidth  = imgH * scale  (horizontal span on page)
						//   scaledHeight = imgW * scale  (vertical span on page)
						//
						// Transform matrix for 90° CCW rotation scaled by `scale`:
						//   [ 0, scale, -scale, 0, tx, ty ]
						// where tx,ty position the bottom-left of the drawn image.
						// A point (0,0) on the image → page (tx, ty)
						// A point (imgW,0)            → page (tx - imgH*scale... no)
						// Let's derive carefully:
						// page_x = a*img_x + c*img_y + tx  = 0*img_x + (-scale)*img_y + tx
						// page_y = b*img_x + d*img_y + ty  = scale*img_x + 0*img_y + ty
						// Corner (0,0)     → (tx, ty)
						// Corner (imgW,0)  → (tx, ty + imgW*scale)  = (tx, ty + scaledHeight)
						// Corner (0,imgH)  → (tx - imgH*scale, ty)  = (tx - scaledWidth, ty)
						// Corner (imgW,imgH)→(tx - scaledWidth, ty + scaledHeight)
						// So the bounding box on page:
						//   x_min = tx - scaledWidth,  x_max = tx
						//   y_min = ty,                 y_max = ty + scaledHeight
						// We want x_min = x, y_min = y:
						//   tx = x + scaledWidth
						//   ty = y
						cs.transform(
							new org.apache.pdfbox.util.Matrix(
								0,           scale,
								-scale,      0,
								x + scaledWidth, y
							)
						);
						cs.drawImage(image, 0, 0, imgW, imgH);

						if (rotate) {
							System.out.println("  → Rotated 90° CCW for better page coverage");
						}
					} else {
						cs.drawImage(image, x, y, scaledWidth, scaledHeight);
					}
				}
			}

			document.save(outputPdfPath);
		}
	}

	/**
	 * Helper class to wrap a File for use in the image list.
	 */
	private static class ImageFile {
		private final File file;

		public ImageFile(File file) {
			this.file = file;
		}

		public File getFile() { return file; }
	}
}