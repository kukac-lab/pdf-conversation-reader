package uk.colinkuka.pdfvoice;

import java.util.*;
import java.util.regex.*;

public final class Dialogue {
    public static final class Turn {
        public final String speaker, text;
        public Turn(String speaker, String text) { this.speaker = speaker; this.text = text; }
    }
    // Explicit name-colon labels only; inference is intentionally editable by the user.
    private static final Pattern LABEL = Pattern.compile("^([\\p{L}][\\p{L}\\p{N} _.'’-]{0,35}):\\s*(.*)$");
    public static List<Turn> parse(String input) {
        List<Turn> turns = new ArrayList<>();
        String speaker = "Narrator";
        StringBuilder text = new StringBuilder();
        for (String raw : input.replace("\r", "").split("\n")) {
            String line = raw.trim();
            Matcher m = LABEL.matcher(line);
            if (m.matches()) {
                flush(turns, speaker, text);
                speaker = m.group(1).trim();
                text.append(m.group(2));
            } else if (line.isEmpty()) {
                flush(turns, speaker, text);
            } else {
                if (text.length() > 0) text.append(' ');
                text.append(line);
            }
        }
        flush(turns, speaker, text);
        return turns;
    }
    private static void flush(List<Turn> turns, String speaker, StringBuilder b) {
        String remaining = b.toString().trim(); b.setLength(0);
        // Short chunks allow reliable pause/replay across Android TTS engines.
        while (!remaining.isEmpty()) {
            int end = Math.min(350, remaining.length());
            if (end < remaining.length()) {
                int space = remaining.lastIndexOf(' ', end);
                if (space > 0) end = space;
                if (Character.isHighSurrogate(remaining.charAt(end-1))) end--;
            }
            turns.add(new Turn(speaker, remaining.substring(0, end).trim()));
            remaining = remaining.substring(end).trim();
        }
    }
}
