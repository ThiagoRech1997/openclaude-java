package dev.openclaude.tools.fileread;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.openclaude.core.model.ContentBlock;
import dev.openclaude.tools.*;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Reads a file and returns its contents. Supports plain text (with line numbers and
 * offset/limit), images (PNG/JPG/GIF/WebP — returned as base64 Image blocks), PDFs
 * (text extraction with page range, via PDFBox), and Jupyter notebooks (.ipynb —
 * cells with outputs including inline PNG images).
 */
public class FileReadTool implements Tool {

    private static final int DEFAULT_MAX_LINES = 2000;
    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024; // Anthropic limit
    private static final int MAX_PDF_PAGES = 20;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final JsonNode SCHEMA = SchemaBuilder.object()
            .stringProp("file_path", "The absolute path to the file to read.", true)
            .intProp("offset", "Line number to start reading from (0-based).", false)
            .intProp("limit", "Maximum number of lines to read.", false)
            .stringProp("pages",
                    "Page range for PDF files (e.g. '1-5', '3'). Max 20 pages per request.", false)
            .build();

    @Override
    public String name() {
        return "Read";
    }

    @Override
    public String description() {
        return "Reads a file from the filesystem. Supports text (with line numbers), "
                + "images (PNG/JPG/GIF/WebP), PDFs (text extraction with 'pages' parameter), "
                + "and Jupyter notebooks (.ipynb).";
    }

    @Override
    public JsonNode inputSchema() {
        return SCHEMA;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ToolResult execute(JsonNode input, ToolUseContext context) {
        String filePath = input.path("file_path").asText("");
        if (filePath.isBlank()) {
            return ToolResult.error("file_path is required.");
        }

        Path path = resolvePath(filePath, context.workingDirectory());

        if (!Files.exists(path)) {
            return ToolResult.error("File does not exist: " + path);
        }

        if (Files.isDirectory(path)) {
            return ToolResult.error("Path is a directory, not a file: " + path
                    + ". Use Bash with 'ls' to list directory contents.");
        }

        String ext = extensionOf(path);
        ToolResult result = switch (ext) {
            case "png", "jpg", "jpeg", "gif", "webp" -> readImage(path, ext);
            case "pdf" -> readPdf(path, input.path("pages").asText(""));
            case "ipynb" -> readNotebook(path);
            default -> readText(path, input);
        };

        if (!result.isError()) {
            context.readFiles().add(path.toAbsolutePath().normalize());
        }
        return result;
    }

    private ToolResult readText(Path path, JsonNode input) {
        int offset = input.has("offset") ? input.get("offset").asInt(0) : 0;
        int limit = input.has("limit") ? input.get("limit").asInt(DEFAULT_MAX_LINES) : DEFAULT_MAX_LINES;

        try {
            List<String> allLines = Files.readAllLines(path);
            int totalLines = allLines.size();

            if (offset >= totalLines && totalLines > 0) {
                return ToolResult.error("Offset " + offset + " exceeds file length of " + totalLines + " lines.");
            }

            int end = Math.min(offset + limit, totalLines);
            List<String> lines = allLines.subList(offset, end);

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                int lineNum = offset + i + 1;
                sb.append(lineNum).append('\t').append(lines.get(i)).append('\n');
            }

            if (end < totalLines) {
                sb.append("\n... (").append(totalLines - end).append(" more lines not shown)\n");
            }

            return ToolResult.success(sb.toString());

        } catch (IOException e) {
            return ToolResult.error("Failed to read file: " + e.getMessage());
        }
    }

    private ToolResult readImage(Path path, String ext) {
        try {
            long size = Files.size(path);
            if (size > MAX_IMAGE_BYTES) {
                return ToolResult.error("Image exceeds 5 MB limit: " + size + " bytes at " + path);
            }
            byte[] bytes = Files.readAllBytes(path);
            String base64 = Base64.getEncoder().encodeToString(bytes);
            String mediaType = mediaTypeFor(ext);
            return ToolResult.success(List.of(new ContentBlock.Image(mediaType, base64)));
        } catch (IOException e) {
            return ToolResult.error("Failed to read image: " + e.getMessage());
        }
    }

    private ToolResult readPdf(Path path, String pagesParam) {
        try (PDDocument doc = Loader.loadPDF(new RandomAccessReadBufferedFile(path.toFile()))) {
            int total = doc.getNumberOfPages();
            if (total == 0) {
                return ToolResult.error("PDF has no pages: " + path);
            }

            int[] range = parsePageRange(pagesParam, total);
            int start = range[0];
            int end = range[1];

            if (end - start + 1 > MAX_PDF_PAGES) {
                end = start + MAX_PDF_PAGES - 1;
            }

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(start);
            stripper.setEndPage(end);
            String text = stripper.getText(doc);

            StringBuilder sb = new StringBuilder();
            sb.append("[PDF pages ").append(start).append('-').append(end)
                    .append(" of ").append(total).append("]\n\n");
            sb.append(text);
            if (end - start + 1 >= MAX_PDF_PAGES && end < total) {
                sb.append("\n... (").append(total - end).append(" more pages not shown; max ")
                        .append(MAX_PDF_PAGES).append(" per request)\n");
            }
            return ToolResult.success(sb.toString());
        } catch (IOException e) {
            return ToolResult.error("Failed to read PDF: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolResult.error("Invalid pages parameter: " + e.getMessage());
        }
    }

    private ToolResult readNotebook(Path path) {
        try {
            JsonNode root = MAPPER.readTree(path.toFile());
            JsonNode cells = root.path("cells");
            if (!cells.isArray()) {
                return ToolResult.error("Invalid notebook: missing 'cells' array.");
            }

            List<ContentBlock> blocks = new ArrayList<>();
            StringBuilder buffer = new StringBuilder();
            int idx = 0;
            for (JsonNode cell : cells) {
                idx++;
                String cellType = cell.path("cell_type").asText("unknown");
                buffer.append("[Cell ").append(idx).append(" — ").append(cellType).append("]\n");
                appendSource(buffer, cell.path("source"));
                buffer.append('\n');

                JsonNode outputs = cell.path("outputs");
                if (outputs.isArray()) {
                    for (JsonNode out : outputs) {
                        appendOutput(buffer, blocks, out);
                    }
                }
                buffer.append('\n');
            }

            if (buffer.length() > 0) {
                blocks.add(0, new ContentBlock.Text(buffer.toString()));
            }
            return ToolResult.success(blocks);
        } catch (IOException e) {
            return ToolResult.error("Failed to read notebook: " + e.getMessage());
        }
    }

    private void appendSource(StringBuilder sb, JsonNode source) {
        if (source.isTextual()) {
            sb.append(source.asText());
        } else if (source.isArray()) {
            for (JsonNode line : source) {
                sb.append(line.asText());
            }
        }
    }

    private void appendOutput(StringBuilder sb, List<ContentBlock> blocks, JsonNode output) {
        String type = output.path("output_type").asText("");
        switch (type) {
            case "stream" -> {
                sb.append("  [stream:").append(output.path("name").asText("stdout")).append("]\n");
                appendSource(sb, output.path("text"));
                sb.append('\n');
            }
            case "execute_result", "display_data" -> {
                JsonNode data = output.path("data");
                JsonNode png = data.path("image/png");
                if (png.isTextual() && !png.asText().isEmpty()) {
                    sb.append("  [output: image/png inline below]\n");
                    blocks.add(new ContentBlock.Image("image/png", png.asText()));
                }
                JsonNode plain = data.path("text/plain");
                if (plain.isTextual() || plain.isArray()) {
                    sb.append("  [output:text/plain]\n");
                    appendSource(sb, plain);
                    sb.append('\n');
                }
            }
            case "error" -> {
                sb.append("  [error:").append(output.path("ename").asText("")).append("] ");
                sb.append(output.path("evalue").asText("")).append('\n');
            }
            default -> { /* skip unknown */ }
        }
    }

    private int[] parsePageRange(String spec, int total) {
        if (spec == null || spec.isBlank()) {
            return new int[] { 1, Math.min(MAX_PDF_PAGES, total) };
        }
        String s = spec.trim();
        int start;
        int end;
        int dash = s.indexOf('-');
        if (dash >= 0) {
            start = Integer.parseInt(s.substring(0, dash).trim());
            end = Integer.parseInt(s.substring(dash + 1).trim());
        } else {
            start = Integer.parseInt(s);
            end = start;
        }
        if (start < 1) start = 1;
        if (end > total) end = total;
        if (start > end) {
            throw new IllegalArgumentException("start page " + start + " after end page " + end);
        }
        return new int[] { start, end };
    }

    private static String extensionOf(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : "";
    }

    private static String mediaTypeFor(String ext) {
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            default -> "application/octet-stream";
        };
    }

    private Path resolvePath(String filePath, Path workingDir) {
        Path p = Path.of(filePath);
        if (p.isAbsolute()) return p;
        return workingDir.resolve(p);
    }
}
