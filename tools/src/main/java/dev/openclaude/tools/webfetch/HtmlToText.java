package dev.openclaude.tools.webfetch;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight HTML-to-text converter using regex.
 * Designed for documentation and API reference pages — not a full HTML parser.
 */
public final class HtmlToText {

    private HtmlToText() {}

    // Patterns for removing non-content blocks (DOTALL so . matches newlines)
    private static final Pattern SCRIPT = Pattern.compile("<script[^>]*>.*?</script>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE = Pattern.compile("<style[^>]*>.*?</style>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern NAV = Pattern.compile("<nav[^>]*>.*?</nav>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern FOOTER = Pattern.compile("<footer[^>]*>.*?</footer>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern HEADER = Pattern.compile("<header[^>]*>.*?</header>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern SVG = Pattern.compile("<svg[^>]*>.*?</svg>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    // Structural tag patterns
    private static final Pattern HEADING = Pattern.compile("<h([1-6])[^>]*>(.*?)</h\\1>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern ANCHOR = Pattern.compile("<a[^>]+href=\"([^\"]*)\"[^>]*>(.*?)</a>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern PRE_BLOCK = Pattern.compile("<pre[^>]*>(.*?)</pre>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern CODE_INLINE = Pattern.compile("<code[^>]*>(.*?)</code>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern LIST_ITEM = Pattern.compile("<li[^>]*>(.*?)</li>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern PARAGRAPH = Pattern.compile("</?p[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern BR = Pattern.compile("<br\\s*/?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern BLOCK_TAGS = Pattern.compile("</?(?:div|section|article|main|aside|blockquote|table|tr|td|th|thead|tbody|ul|ol|dl|dt|dd|figure|figcaption)[^>]*>", Pattern.CASE_INSENSITIVE);

    // Remaining tags
    private static final Pattern ANY_TAG = Pattern.compile("<[^>]+>");

    // HTML entities
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(\\d+);");
    private static final Pattern HEX_ENTITY = Pattern.compile("&#x([0-9a-fA-F]+);");

    // Whitespace cleanup
    private static final Pattern MULTIPLE_NEWLINES = Pattern.compile("\n{3,}");
    private static final Pattern TRAILING_SPACES = Pattern.compile("[ \t]+\n");
    private static final Pattern LEADING_TRAILING_WS = Pattern.compile("^\\s+|\\s+$");

    /**
     * Convert HTML to readable plain text with light markdown formatting.
     */
    public static String convert(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }

        String text = html;

        // 1. Remove non-content blocks
        text = SCRIPT.matcher(text).replaceAll("");
        text = STYLE.matcher(text).replaceAll("");
        text = NAV.matcher(text).replaceAll("");
        text = FOOTER.matcher(text).replaceAll("");
        text = HEADER.matcher(text).replaceAll("");
        text = SVG.matcher(text).replaceAll("");

        // 2. Convert <pre> blocks to fenced code blocks (before other conversions)
        text = replacePreBlocks(text);

        // 3. Convert headings: <h1>text</h1> → # text
        text = replaceHeadings(text);

        // 4. Convert links: <a href="url">text</a> → [text](url)
        text = ANCHOR.matcher(text).replaceAll("[$2]($1)");

        // 5. Convert inline code
        text = CODE_INLINE.matcher(text).replaceAll("`$1`");

        // 6. Convert list items
        text = LIST_ITEM.matcher(text).replaceAll("\n- $1\n");

        // 7. Convert block elements to newlines
        text = PARAGRAPH.matcher(text).replaceAll("\n\n");
        text = BR.matcher(text).replaceAll("\n");
        text = BLOCK_TAGS.matcher(text).replaceAll("\n");

        // 8. Strip all remaining HTML tags
        text = ANY_TAG.matcher(text).replaceAll("");

        // 9. Decode HTML entities
        text = decodeEntities(text);

        // 10. Clean up whitespace
        text = TRAILING_SPACES.matcher(text).replaceAll("\n");
        text = MULTIPLE_NEWLINES.matcher(text).replaceAll("\n\n");
        text = LEADING_TRAILING_WS.matcher(text).replaceAll("");

        return text;
    }

    private static String replacePreBlocks(String text) {
        Matcher m = PRE_BLOCK.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String content = m.group(1);
            // Remove inner <code> tags if present
            content = content.replaceAll("(?i)</?code[^>]*>", "");
            // Strip any remaining tags inside pre
            content = ANY_TAG.matcher(content).replaceAll("");
            m.appendReplacement(sb, Matcher.quoteReplacement("\n```\n" + content.strip() + "\n```\n"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String replaceHeadings(String text) {
        Matcher m = HEADING.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int level = Integer.parseInt(m.group(1));
            String content = ANY_TAG.matcher(m.group(2)).replaceAll("").strip();
            String prefix = "#".repeat(level);
            m.appendReplacement(sb, Matcher.quoteReplacement("\n\n" + prefix + " " + content + "\n\n"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String decodeEntities(String text) {
        text = text.replace("&amp;", "&");
        text = text.replace("&lt;", "<");
        text = text.replace("&gt;", ">");
        text = text.replace("&quot;", "\"");
        text = text.replace("&apos;", "'");
        text = text.replace("&nbsp;", " ");

        // Numeric entities: &#NNN;
        Matcher numMatcher = NUMERIC_ENTITY.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (numMatcher.find()) {
            int codePoint = Integer.parseInt(numMatcher.group(1));
            numMatcher.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf((char) codePoint)));
        }
        numMatcher.appendTail(sb);
        text = sb.toString();

        // Hex entities: &#xHHH;
        Matcher hexMatcher = HEX_ENTITY.matcher(text);
        sb = new StringBuilder();
        while (hexMatcher.find()) {
            int codePoint = Integer.parseInt(hexMatcher.group(1), 16);
            hexMatcher.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf((char) codePoint)));
        }
        hexMatcher.appendTail(sb);

        return sb.toString();
    }
}
