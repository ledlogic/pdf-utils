package com.github.ledlogic.pdfutils;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * PDF Text Extractor - Extracts plain text from PDF files
 * and saves it to a markup document with font usage statistics.
 * Accepts either a single PDF file or a folder containing PDF files.
 */
public class PDFTextExtractor {

    /**
     * Custom PDFTextStripper that tracks font usage
     */
    private static class FontTrackingStripper extends PDFTextStripper {
        private Map<String, Integer> fontCounts = new HashMap<>();
        private int totalCharacters = 0;

        public FontTrackingStripper() throws IOException {
            super();
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
            super.writeString(text, textPositions);
            
            // Track font usage for each character
            for (TextPosition position : textPositions) {
                PDFont font = position.getFont();
                if (font != null) {
                    String fontName = font.getName();
                    if (fontName == null) {
                        fontName = "Unknown";
                    }
                    fontCounts.put(fontName, fontCounts.getOrDefault(fontName, 0) + 1);
                    totalCharacters++;
                }
            }
        }

        public Map<String, Integer> getFontCounts() {
            return fontCounts;
        }

        public int getTotalCharacters() {
            return totalCharacters;
        }
    }

    /**
     * Extracts text from a PDF file and saves it to a markdown file.
     * Also creates a font usage log file.
     * 
     * @param pdfPath Path to the input PDF file
     * @param outputPath Path to the output markdown file
     * @throws IOException if file operations fail
     */
    public static void extractTextToMarkdown(String pdfPath, String outputPath) throws IOException {
        File pdfFile = new File(pdfPath);
        
        if (!pdfFile.exists()) {
            throw new IOException("PDF file not found: " + pdfPath);
        }
        
        // Load the PDF document
        try (PDDocument document = PDDocument.load(pdfFile)) {
            
            // Create custom text stripper with font tracking
            FontTrackingStripper stripper = new FontTrackingStripper();
            
            // Configure stripper to preserve some formatting
            stripper.setSortByPosition(true);
            stripper.setLineSeparator("\n");
            
            // Extract text from all pages
            String text = stripper.getText(document);
            
            // Get font statistics
            Map<String, Integer> fontCounts = stripper.getFontCounts();
            int totalChars = stripper.getTotalCharacters();
            
            // Clean up the text (remove excessive blank lines)
            text = cleanText(text);
            
            // Write to output file
            try (FileWriter writer = new FileWriter(outputPath)) {
                // Add markdown header
                writer.write("# Extracted Text from PDF\n\n");
                writer.write("**Source:** " + pdfFile.getName() + "\n\n");
                writer.write("**Pages:** " + document.getNumberOfPages() + "\n\n");
                writer.write("---\n\n");
                
                // Write the extracted text
                writer.write(text);
            }
            
            // Write font statistics to log file
            String fontLogPath = outputPath.replace(".md", "_fonts.log");
            writeFontLog(fontLogPath, fontCounts, totalChars, pdfFile.getName());
            
            System.out.println("Text extraction complete!");
            System.out.println("Input: " + pdfPath);
            System.out.println("Output: " + outputPath);
            System.out.println("Font log: " + fontLogPath);
            System.out.println("Pages processed: " + document.getNumberOfPages());
            System.out.println("Total characters: " + totalChars);
            System.out.println("Unique fonts: " + fontCounts.size());
        }
    }

    /**
     * Processes all PDF files found in the given folder.
     * Output .md and _fonts.log files are written alongside each PDF.
     *
     * @param folderPath Path to the folder containing PDF files
     * @throws IOException if the folder cannot be read
     */
    public static void extractTextFromFolder(String folderPath) throws IOException {
        File folder = new File(folderPath);

        if (!folder.isDirectory()) {
            throw new IOException("Not a directory: " + folderPath);
        }

        File[] pdfFiles = folder.listFiles(
            (dir, name) -> name.toLowerCase().endsWith(".pdf")
        );

        if (pdfFiles == null || pdfFiles.length == 0) {
            System.out.println("No PDF files found in folder: " + folderPath);
            return;
        }

        System.out.println("Found " + pdfFiles.length + " PDF file(s) in: " + folderPath);
        System.out.println();

        int succeeded = 0;
        int failed = 0;

        for (File pdfFile : pdfFiles) {
            String pdfPath = pdfFile.getAbsolutePath();
            String outputPath = generateOutputPath(pdfPath);

            System.out.println("Processing: " + pdfFile.getName());
            try {
                extractTextToMarkdown(pdfPath, outputPath);
                succeeded++;
            } catch (IOException e) {
                System.err.println("  ERROR processing " + pdfFile.getName() + ": " + e.getMessage());
                failed++;
            }
            System.out.println();
        }

        System.out.println("Folder processing complete.");
        System.out.println("Succeeded: " + succeeded + "  Failed: " + failed);
    }
    
    /**
     * Writes font usage statistics to a log file
     * 
     * @param logPath Path to the output log file
     * @param fontCounts Map of font names to character counts
     * @param totalChars Total number of characters
     * @param sourceName Name of the source PDF file
     * @throws IOException if file writing fails
     */
    private static void writeFontLog(String logPath, Map<String, Integer> fontCounts, 
                                     int totalChars, String sourceName) throws IOException {
        // Sort fonts by frequency (descending)
        List<Map.Entry<String, Integer>> sortedFonts = fontCounts.entrySet()
            .stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .collect(Collectors.toList());
        
        try (FileWriter writer = new FileWriter(logPath)) {
            writer.write("Font Usage Report\n");
            writer.write("=================\n\n");
            writer.write("Source: " + sourceName + "\n");
            writer.write("Total Characters: " + totalChars + "\n");
            writer.write("Unique Fonts: " + fontCounts.size() + "\n\n");
            writer.write("Font Statistics:\n");
            writer.write("----------------\n\n");
            
            // Write each font with its usage statistics
            for (Map.Entry<String, Integer> entry : sortedFonts) {
                String fontName = entry.getKey();
                int count = entry.getValue();
                double percentage = (totalChars > 0) ? (count * 100.0 / totalChars) : 0.0;
                
                writer.write(String.format("%-50s %8d chars  %6.2f%%\n", 
                    fontName, count, percentage));
            }
            
            writer.write("\n");
            writer.write("Note: Percentages are based on total character count.\n");
            writer.write("Font names include style variants (e.g., Bold, Italic).\n");
        }
    }
    
    /**
     * Cleans up extracted text by removing excessive blank lines.
     * 
     * @param text The raw extracted text
     * @return Cleaned text
     */
    private static String cleanText(String text) {
        // Replace multiple consecutive blank lines with just two newlines
        text = text.replaceAll("\n{3,}", "\n\n");
        
        // Remove trailing whitespace from lines
        text = text.replaceAll("[ \t]+\n", "\n");
        
        // Trim leading and trailing whitespace
        text = text.trim();
        
        return text;
    }
    
    /**
     * Generates the output filename by replacing the PDF extension with .md
     * 
     * @param pdfPath The input PDF file path
     * @return The output markdown file path
     */
    private static String generateOutputPath(String pdfPath) {
        // Remove .pdf extension (case insensitive) and add .md
        if (pdfPath.toLowerCase().endsWith(".pdf")) {
            return pdfPath.substring(0, pdfPath.length() - 4) + ".md";
        } else {
            // If it doesn't end with .pdf, just append .md
            return pdfPath + ".md";
        }
    }
    
    /**
     * Main method for command-line usage.
     * Accepts either a single PDF file or a folder of PDF files.
     *
     * @param args Command line arguments: [input_pdf_path_or_folder]
     */
    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java PDFTextExtractor <input_pdf_path_or_folder>");
            System.err.println("Examples:");
            System.err.println("  java PDFTextExtractor document.pdf");
            System.err.println("  java PDFTextExtractor /path/to/pdf/folder");
            System.err.println("Output: each PDF produces a .md file and a _fonts.log file.");
            System.exit(1);
        }
        
        String inputPath = args[0];
        File input = new File(inputPath);

        try {
            if (input.isDirectory()) {
                // Process every PDF in the folder
                extractTextFromFolder(inputPath);
            } else {
                // Process a single PDF file
                String outputPath = generateOutputPath(inputPath);
                extractTextToMarkdown(inputPath, outputPath);
            }
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}