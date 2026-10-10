package com.github.ledlogic.pdfutils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * PDF Extractor - runs both image and text extraction on each PDF.
 *
 * Accepts a single PDF file or a directory. Directories are searched
 * recursively and PDFs are processed in full-path order.
 *
 * Each PDF is loaded once and produces:
 *   - &lt;name&gt;/          folder of extracted images   (see {@link PDFImgExtractor})
 *   - &lt;name&gt;.md        extracted text as markdown   (see {@link PDFTextExtractor})
 *   - &lt;name&gt;_fonts.log font usage report           (see {@link PDFTextExtractor})
 *
 * A failure in one stage is reported but does not stop the other stage,
 * and a failure on one PDF does not stop the rest of the folder.
 */
public class PDFExtractor {

	private static final String RULE = "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━";

	/** Result of processing one PDF. */
	static class Result {
		boolean imagesOk;
		boolean textOk;

		boolean allOk() {
			return imagesOk && textOk;
		}
	}

	public static void main(String[] args) {
		if (args.length != 1) {
			System.err.println("Usage: java PDFExtractor <path-to-pdf-file-or-folder>");
			System.err.println("Examples:");
			System.err.println("  java PDFExtractor scenario.pdf");
			System.err.println("  java PDFExtractor /path/to/pdf/folder   (searched recursively)");
			System.err.println("Output per PDF: <name>/ (images), <name>.md (text), <name>_fonts.log (fonts)");
			System.exit(1);
		}

		File input = new File(args[0]);

		if (!input.exists()) {
			System.err.println("Error: Path not found: " + args[0]);
			System.exit(1);
		}

		if (input.isDirectory()) {
			int failures = processFolder(input);
			System.exit(failures == 0 ? 0 : 2);
		} else if (input.isFile()) {
			if (!input.getName().toLowerCase().endsWith(".pdf")) {
				System.err.println("Warning: File does not have a .pdf extension: " + args[0]);
			}
			Result r = processPDF(input);
			System.exit(r.allOk() ? 0 : 2);
		} else {
			System.err.println("Error: Path is neither a file nor a directory: " + args[0]);
			System.exit(1);
		}
	}

	/**
	 * Run image and text extraction on a single PDF, loading it only once.
	 */
	public static Result processPDF(File pdfFile) {
		Result result = new Result();

		try (PDDocument document = PDDocument.load(pdfFile)) {

			// --- Stage 1: images ---
			System.out.println("── Images ──");
			try {
				new PDFImgExtractor(null).extractImages(document, pdfFile);
				result.imagesOk = true;
			} catch (Exception e) {
				System.err.println("Image extraction failed for " + pdfFile.getName() + ": " + e.getMessage());
			}

			// --- Stage 2: text + font log ---
			System.out.println();
			System.out.println("── Text ──");
			try {
				String mdPath = PDFTextExtractor.generateOutputPath(pdfFile.getAbsolutePath());
				PDFTextExtractor.extractTextToMarkdown(document, pdfFile, mdPath);
				result.textOk = true;
			} catch (Exception e) {
				System.err.println("Text extraction failed for " + pdfFile.getName() + ": " + e.getMessage());
			}

		} catch (Exception e) {
			System.err.println("Could not open " + pdfFile.getName() + ": " + e.getMessage());
		}

		return result;
	}

	/**
	 * Process every PDF under the folder (recursively).
	 *
	 * @return number of PDFs with at least one failed stage
	 */
	private static int processFolder(File folder) {
		List<File> pdfFiles = new ArrayList<>();
		collectPDFsRecursively(folder, pdfFiles);

		if (pdfFiles.isEmpty()) {
			System.out.println("No PDF files found under: " + folder.getAbsolutePath());
			return 0;
		}

		pdfFiles.sort((a, b) -> a.getAbsolutePath().compareToIgnoreCase(b.getAbsolutePath()));

		String rootPath = folder.getAbsolutePath();
		System.out.println("=== Folder Mode (recursive) ===");
		System.out.println("Root directory: " + rootPath);
		System.out.println("Found " + pdfFiles.size() + " PDF file(s) to process:");
		for (int i = 0; i < pdfFiles.size(); i++) {
			System.out.println("  " + (i + 1) + ". " + relativize(rootPath, pdfFiles.get(i)));
		}
		System.out.println();

		int ok = 0;
		List<String> failed = new ArrayList<>();

		for (int i = 0; i < pdfFiles.size(); i++) {
			File pdfFile = pdfFiles.get(i);
			String rel = relativize(rootPath, pdfFile);

			System.out.println(RULE);
			System.out.println("Processing file " + (i + 1) + " of " + pdfFiles.size() + ": " + rel);
			System.out.println(RULE);

			Result r = processPDF(pdfFile);
			if (r.allOk()) {
				ok++;
			} else {
				String which = !r.imagesOk && !r.textOk ? "images + text"
						: !r.imagesOk ? "images" : "text";
				failed.add(rel + "  (" + which + ")");
			}
			System.out.println();
		}

		System.out.println(RULE);
		System.out.println("=== Folder Processing Complete ===");
		System.out.println("Fully processed: " + ok + " file(s)");
		if (!failed.isEmpty()) {
			System.out.println("With failures:   " + failed.size() + " file(s)");
			for (String f : failed) {
				System.out.println("  ✗ " + f);
			}
		}
		System.out.println(RULE);

		return failed.size();
	}

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

	private static String relativize(String rootPath, File file) {
		String path = file.getAbsolutePath();
		return path.startsWith(rootPath + File.separator) ? path.substring(rootPath.length() + 1) : path;
	}
}
