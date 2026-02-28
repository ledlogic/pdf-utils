package com.github.ledlogic.pdfutils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
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
 * This tool scans a directory for files matching the pattern "XX-room name.png"
 * where XX is a two-digit number, and creates a PDF with one image per page,
 * ordered by the numeric prefix.
 * 
 * Usage: java ImageToPdfAssembler <input_directory> <output_pdf> Example: java
 * ImageToPdfAssembler /path/to/images output.pdf
 */
public class ImageToPdfAssembler {

	// Letter size in points (1 inch = 72 points)
	private static final PDRectangle LETTER = PDRectangle.LETTER; // 8.5" x 11"

	// Pattern to match files like "00-room name.png"
	private static final Pattern FILE_PATTERN = Pattern.compile("^(\\d{2})-.*\\.png$", Pattern.CASE_INSENSITIVE);

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
				System.err.println("No image files matching pattern 'XX-*.png' found in directory: " + inputDirectory);
				System.exit(1);
			}

			System.out.println("Found " + imageFiles.size() + " image(s) to process:");
			for (ImageFile imgFile : imageFiles) {
				System.out.println("  " + imgFile.getIndex() + " - " + imgFile.getFile().getName());
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
	 * Find all PNG files matching the pattern and sort them by numeric prefix
	 */
	private static List<ImageFile> findAndSortImages(String directoryPath) throws IOException {
		Path dir = Paths.get(directoryPath);

		if (!Files.exists(dir) || !Files.isDirectory(dir)) {
			throw new IOException("Directory does not exist or is not a directory: " + directoryPath);
		}

		List<ImageFile> imageFiles = Files.list(dir).filter(Files::isRegularFile).map(Path::toFile).filter(file -> {
			Matcher matcher = FILE_PATTERN.matcher(file.getName());
			return matcher.matches();
		}).map(ImageFile::new).collect(Collectors.toList());

		Collections.sort(imageFiles);

		return imageFiles;
	}

	/**
	 * Create a PDF with one image per page
	 */
	private static void createPdf(List<ImageFile> imageFiles, String outputPdfPath) throws IOException {
		try (PDDocument document = new PDDocument()) {

			for (ImageFile imageFile : imageFiles) {
				File file = imageFile.getFile();
				System.out.println("Processing: " + file.getName());

				// Create a new page with letter size in portrait orientation
				PDPage page = new PDPage(LETTER);
				document.addPage(page);

				// Load the image
				PDImageXObject image = PDImageXObject.createFromFile(file.getAbsolutePath(), document);

				// Calculate dimensions to fit the image on the page while maintaining aspect
				// ratio
				float pageWidth = LETTER.getWidth();
				float pageHeight = LETTER.getHeight();
				float imageWidth = image.getWidth();
				float imageHeight = image.getHeight();

				// Calculate scaling factor to fit image on page
				float scaleWidth = pageWidth / imageWidth;
				float scaleHeight = pageHeight / imageHeight;
				float scale = Math.min(scaleWidth, scaleHeight);

				float scaledWidth = imageWidth * scale;
				float scaledHeight = imageHeight * scale;

				// Center the image on the page
				float x = (pageWidth - scaledWidth) / 2;
				float y = (pageHeight - scaledHeight) / 2;

				// Draw the image on the page
				try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
					contentStream.drawImage(image, x, y, scaledWidth, scaledHeight);
				}
			}

			// Save the document
			document.save(outputPdfPath);
		}
	}

	/**
	 * Helper class to store file information and enable sorting
	 */
	private static class ImageFile implements Comparable<ImageFile> {
		private final File file;
		private final int index;

		public ImageFile(File file) {
			this.file = file;
			this.index = extractIndex(file.getName());
		}

		private int extractIndex(String filename) {
			Matcher matcher = FILE_PATTERN.matcher(filename);
			if (matcher.matches()) {
				return Integer.parseInt(matcher.group(1));
			}
			return Integer.MAX_VALUE; // Should never happen if pattern matched
		}

		public File getFile() {
			return file;
		}

		public int getIndex() {
			return index;
		}

		@Override
		public int compareTo(ImageFile other) {
			return Integer.compare(this.index, other.index);
		}
	}
}
