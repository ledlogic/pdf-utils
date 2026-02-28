package com.github.ledlogic.pdfutils;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/**
 * Tool to extract images from all pages of a PDF file. Images are saved to a
 * folder named after the PDF file (minus the .pdf suffix). For example,
 * "my-document.pdf" → output folder "my-document/"
 */
public class PDFImgExtractorTest {

	public static void main(String[] args) {
		if (args.length < 1) {
			System.out.println("Usage: java PDFImageExtractorTest <path-to-pdf-file>");
			System.exit(1);
		}

		String pdfPath = args[0];
		File pdfFile = new File(pdfPath);

		if (!pdfFile.exists()) {
			System.err.println("Error: PDF file not found: " + pdfPath);
			System.exit(1);
		}

		// Derive output folder name from PDF filename (strip .pdf suffix)
		String pdfName = pdfFile.getName();
		String folderName = pdfName.toLowerCase().endsWith(".pdf") ? pdfName.substring(0, pdfName.length() - 4)
				: pdfName;

		// Place output folder alongside the PDF file
		File outputDir = new File(pdfFile.getParentFile(), folderName);
		if (!outputDir.exists() && !outputDir.mkdirs()) {
			System.err.println("Error: Could not create output directory: " + outputDir.getAbsolutePath());
			System.exit(1);
		}

		System.out.println("PDF:           " + pdfFile.getName());
		System.out.println("Output folder: " + outputDir.getAbsolutePath());
		System.out.println();

		try (PDDocument document = PDDocument.load(pdfFile)) {
			int totalPages = document.getNumberOfPages();
			System.out.println("Total pages: " + totalPages);
			System.out.println();

			int totalImagesExtracted = 0;

			for (int pageIndex = 0; pageIndex < totalPages; pageIndex++) {
				int pageNumber = pageIndex + 1; // 1-based for display
				PDPage page = document.getPage(pageIndex);

				PDResources resources = page.getResources();
				if (resources == null) {
					System.out.println("Page " + pageNumber + ": no resources, skipping.");
					continue;
				}

				int imageCountOnPage = 0;
				for (COSName name : resources.getXObjectNames()) {
					PDXObject xObject;
					try {
						xObject = resources.getXObject(name);
					} catch (IOException e) {
						System.out.println("  Page " + pageNumber + " / " + name.getName()
								+ ": failed to load XObject (" + e.getMessage() + ")");
						continue;
					}

					if (!(xObject instanceof PDImageXObject)) {
						continue;
					}

					PDImageXObject image = (PDImageXObject) xObject;

					try {
						BufferedImage bufferedImage = image.getImage();
						if (bufferedImage == null) {
							System.out.println("  Page " + pageNumber + " / " + name.getName()
									+ ": getImage() returned null, skipping.");
							continue;
						}

						// File name: page-0001-img-001.png (zero-padded for clean sorting)
						String outputFileName = String.format("page-%04d-img-%03d.png", pageNumber,
								imageCountOnPage + 1);
						File outputFile = new File(outputDir, outputFileName);

						ImageIO.write(bufferedImage, "PNG", outputFile);
						imageCountOnPage++;
						totalImagesExtracted++;

						System.out.println("  Page " + pageNumber + " / " + name.getName() + " → " + outputFileName
								+ "  (" + image.getWidth() + "x" + image.getHeight() + ")");

					} catch (Exception e) {
						System.out.println("  Page " + pageNumber + " / " + name.getName() + ": extraction failed ("
								+ e.getMessage() + ")");
					}
				}

				if (imageCountOnPage == 0) {
					System.out.println("  Page " + pageNumber + ": no images found.");
				}
			}

			System.out.println();
			System.out.println("========================================");
			System.out.println("Done. Total images extracted: " + totalImagesExtracted);
			System.out.println("Output folder: " + outputDir.getAbsolutePath());
			System.out.println("========================================");

			if (totalImagesExtracted == 0) {
				System.out.println();
				System.out.println("No images were extracted. Possible reasons:");
				System.out.println("  - Images are inline (not XObjects)");
				System.out.println("  - Images are embedded inside Form XObjects");
				System.out.println("  - The PDF contains only vector graphics, not raster images");
			}

		} catch (IOException e) {
			System.err.println("Error processing PDF: " + e.getMessage());
			e.printStackTrace();
			System.exit(1);
		}
	}
}