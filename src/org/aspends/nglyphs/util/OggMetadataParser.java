package org.aspends.nglyphs.util;

import org.aspends.nglyphs.R;

import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.Inflater;

public class OggMetadataParser {

    /**
     * Parses the AUTHOR tag from an OGG file.
     * The tag contains Base64 encoded and zlib compressed 60Hz CSV data for the Nothing Phone (1).
     *
     * @param oggFile The custom imported OGG ringtone file.
     * @return The decompressed timeline string (CSV format), or null if not found/invalid.
     */
    public static String extractGlyphTimeline(File oggFile) {
        if (!oggFile.exists() || !oggFile.canRead()) return null;

        try (FileInputStream fis = new FileInputStream(oggFile)) {
            // Read the first 512KB to ensure we cover any large headers (e.g. art)
            byte[] buffer = new byte[524288];
            int bytesRead = fis.read(buffer);
            if (bytesRead <= 0) return null;

            // 1. Try AUTHOR tag (High-fidelity 60Hz CSV)
            String authorData = tryExtractTag(buffer, bytesRead, "AUTHOR");
            if (authorData != null && authorData.contains(",")) {
                return authorData;
            }

            // 2. Fallback to CUSTOM1 tag (Low-res dot triggers)
            String custom1Data = tryExtractTag(buffer, bytesRead, "CUSTOM1");
            if (custom1Data != null) {
                return convertDotsToCsv(custom1Data);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private static String tryExtractTag(byte[] buffer, int bytesRead, String tagName) {
        byte[] upper = (tagName + "=").toUpperCase().getBytes(StandardCharsets.US_ASCII);
        byte[] lower = (tagName + "=").toLowerCase().getBytes(StandardCharsets.US_ASCII);
        int startIndex = -1;
        int targetLen = 7; // Both AUTHOR= and CUSTOM1= are 7 chars (Wait, CUSTOM1= is 8)
        targetLen = upper.length;

        for (int i = 0; i < bytesRead - targetLen; i++) {
            boolean match = true;
            for (int j = 0; j < targetLen; j++) {
                if (buffer[i + j] != upper[j] && buffer[i + j] != lower[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                startIndex = i + targetLen;
                break;
            }
        }

        if (startIndex == -1) return null;

        StringBuilder base64Builder = new StringBuilder();
        for (int i = startIndex; i < bytesRead; i++) {
            char c = (char) (buffer[i] & 0xFF);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '=') {
                base64Builder.append(c);
            } else if (Character.isWhitespace(c)) {
                continue;
            } else {
                break;
            }
        }
        if (base64Builder.length() == 0) return null;

        try {
            byte[] compressedData = Base64.decode(base64Builder.toString(), Base64.DEFAULT);
            Inflater inflater = new Inflater();
            inflater.setInput(compressedData);
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream(compressedData.length);
            byte[] decompressedBuffer = new byte[1024];

            while (!inflater.finished()) {
                int count = inflater.inflate(decompressedBuffer);
                if (count == 0 && inflater.needsInput()) break;
                outputStream.write(decompressedBuffer, 0, count);
            }
            outputStream.close();
            inflater.end();
            return outputStream.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private static String convertDotsToCsv(String dots) {
        // dots = "time-id,time-id,..."
        java.util.TreeMap<Integer, java.util.BitSet> timeline = new java.util.TreeMap<>();
        String[] parts = dots.split(",");
        int maxFrame = 0;

        for (String part : parts) {
            String[] sub = part.trim().split("-");
            if (sub.length == 2) {
                try {
                    int timeMs = Integer.parseInt(sub[0]);
                    int id = Integer.parseInt(sub[1]);
                    if (id < 1 || id > 5) continue;
                    
                    int startFrame = (int) (timeMs / 16.666);
                    // Light up for ~50ms (3 frames) for visibility
                    for (int f = startFrame; f < startFrame + 3; f++) {
                        if (!timeline.containsKey(f)) timeline.put(f, new java.util.BitSet(5));
                        timeline.get(f).set(id - 1);
                        maxFrame = Math.max(maxFrame, f);
                    }
                } catch (Exception e) {}
            }
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= maxFrame; i++) {
            java.util.BitSet p = timeline.get(i);
            for (int j = 0; j < 5; j++) {
                sb.append((p != null && p.get(j)) ? "4095" : "0");
                sb.append(",");
            }
            sb.append("\r\n");
        }
        return sb.toString();
    }
}
