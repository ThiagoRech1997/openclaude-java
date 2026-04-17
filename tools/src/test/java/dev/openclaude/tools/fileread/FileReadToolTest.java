package dev.openclaude.tools.fileread;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.openclaude.core.model.ContentBlock;
import dev.openclaude.tools.ToolResult;
import dev.openclaude.tools.ToolUseContext;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FileReadToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private FileReadTool tool;

    @TempDir
    Path tempDir;

    private ToolUseContext context() {
        return new ToolUseContext(tempDir, false);
    }

    private ObjectNode inputWithPath(Path p) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("file_path", p.toAbsolutePath().toString());
        return node;
    }

    @BeforeEach
    void setUp() {
        tool = new FileReadTool();
    }

    @Nested
    @DisplayName("Tool metadata")
    class Metadata {

        @Test
        void nameIsRead() {
            assertEquals("Read", tool.name());
        }

        @Test
        void isReadOnly() {
            assertTrue(tool.isReadOnly());
        }

        @Test
        void descriptionMentionsImagesPdfNotebook() {
            String d = tool.description().toLowerCase();
            assertTrue(d.contains("image"));
            assertTrue(d.contains("pdf"));
            assertTrue(d.contains("notebook") || d.contains("ipynb"));
        }

        @Test
        void schemaHasPagesProperty() {
            JsonNode props = tool.inputSchema().path("properties");
            assertTrue(props.has("pages"));
        }
    }

    @Nested
    @DisplayName("Plain text reads")
    class TextRead {

        @Test
        void readsFileWithLineNumbers() throws IOException {
            Path f = tempDir.resolve("hello.txt");
            Files.writeString(f, "alpha\nbeta\ngamma\n");

            ToolResult r = tool.execute(inputWithPath(f), context());

            assertFalse(r.isError());
            String text = r.textContent();
            assertTrue(text.contains("1\talpha"));
            assertTrue(text.contains("2\tbeta"));
            assertTrue(text.contains("3\tgamma"));
        }
    }

    @Nested
    @DisplayName("Image reads")
    class ImageRead {

        @Test
        void readsPngAsBase64ImageBlock() throws IOException {
            Path f = tempDir.resolve("pixel.png");
            BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            ImageIO.write(img, "png", f.toFile());

            ToolResult r = tool.execute(inputWithPath(f), context());

            assertFalse(r.isError());
            List<ContentBlock> blocks = r.content();
            assertEquals(1, blocks.size());
            assertTrue(blocks.get(0) instanceof ContentBlock.Image);
            ContentBlock.Image imgBlock = (ContentBlock.Image) blocks.get(0);
            assertEquals("image/png", imgBlock.source().mediaType());
            assertEquals("base64", imgBlock.source().type());

            byte[] fileBytes = Files.readAllBytes(f);
            String expected = Base64.getEncoder().encodeToString(fileBytes);
            assertEquals(expected, imgBlock.source().data());
        }

        @Test
        void detectsJpegByExtension() throws IOException {
            Path f = tempDir.resolve("pic.jpg");
            BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
            ImageIO.write(img, "jpg", f.toFile());

            ToolResult r = tool.execute(inputWithPath(f), context());

            assertFalse(r.isError());
            ContentBlock.Image imgBlock = (ContentBlock.Image) r.content().get(0);
            assertEquals("image/jpeg", imgBlock.source().mediaType());
        }

        @Test
        void rejectsImagesOverFiveMb() throws IOException {
            Path f = tempDir.resolve("huge.png");
            byte[] buf = new byte[(int) (5L * 1024 * 1024 + 10)];
            Files.write(f, buf);

            ToolResult r = tool.execute(inputWithPath(f), context());

            assertTrue(r.isError());
            assertTrue(r.textContent().contains("5 MB"));
        }
    }

    @Nested
    @DisplayName("PDF reads")
    class PdfRead {

        private Path writePdf(String name, List<String> pageTexts) throws IOException {
            Path f = tempDir.resolve(name);
            try (PDDocument doc = new PDDocument()) {
                for (String text : pageTexts) {
                    PDPage page = new PDPage();
                    doc.addPage(page);
                    try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                        cs.beginText();
                        cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        cs.newLineAtOffset(50, 700);
                        cs.showText(text);
                        cs.endText();
                    }
                }
                doc.save(f.toFile());
            }
            return f;
        }

        @Test
        void extractsTextFromSinglePagePdf() throws IOException {
            Path f = writePdf("one.pdf", List.of("hello-from-pdf"));

            ToolResult r = tool.execute(inputWithPath(f), context());

            assertFalse(r.isError());
            assertTrue(r.textContent().contains("hello-from-pdf"));
        }

        @Test
        void respectsPagesRange() throws IOException {
            Path f = writePdf("multi.pdf", List.of("PAGE-ONE", "PAGE-TWO", "PAGE-THREE"));

            ObjectNode node = inputWithPath(f);
            node.put("pages", "2-3");
            ToolResult r = tool.execute(node, context());

            assertFalse(r.isError());
            String text = r.textContent();
            assertFalse(text.contains("PAGE-ONE"));
            assertTrue(text.contains("PAGE-TWO"));
            assertTrue(text.contains("PAGE-THREE"));
        }

        @Test
        void clampsToTwentyPagesMax() throws IOException {
            java.util.List<String> pages = new java.util.ArrayList<>();
            for (int i = 1; i <= 25; i++) pages.add("P" + i);
            Path f = writePdf("big.pdf", pages);

            ObjectNode node = inputWithPath(f);
            node.put("pages", "1-25");
            ToolResult r = tool.execute(node, context());

            assertFalse(r.isError());
            String text = r.textContent();
            assertTrue(text.contains("P1"));
            assertTrue(text.contains("P20"));
            assertFalse(text.contains("P25"));
            assertTrue(text.contains("more pages not shown"));
        }
    }

    @Nested
    @DisplayName("Jupyter notebook reads")
    class NotebookRead {

        @Test
        void rendersCellsAndInlineImageOutput() throws IOException {
            String pixelPngBase64 = Base64.getEncoder().encodeToString(new byte[] {
                    (byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a
            });
            String ipynb = """
                    {
                      "cells": [
                        {"cell_type": "markdown", "source": ["# Title\\n", "Some text"]},
                        {"cell_type": "code",
                         "source": ["print('hi')"],
                         "outputs": [
                           {"output_type": "stream", "name": "stdout", "text": ["hi\\n"]},
                           {"output_type": "display_data",
                            "data": {"image/png": "%s", "text/plain": ["<Figure>"]}}
                         ]}
                      ]
                    }
                    """.formatted(pixelPngBase64);
            Path f = tempDir.resolve("nb.ipynb");
            Files.writeString(f, ipynb);

            ToolResult r = tool.execute(inputWithPath(f), context());

            assertFalse(r.isError());
            List<ContentBlock> blocks = r.content();
            boolean hasText = blocks.stream().anyMatch(b -> b instanceof ContentBlock.Text);
            boolean hasImage = blocks.stream().anyMatch(b -> b instanceof ContentBlock.Image);
            assertTrue(hasText, "notebook should yield at least one text block");
            assertTrue(hasImage, "inline image/png output should become an Image block");

            String text = r.textContent();
            assertTrue(text.contains("markdown"));
            assertTrue(text.contains("code"));
            assertTrue(text.contains("print('hi')"));
            assertTrue(text.contains("hi"));
        }
    }
}
