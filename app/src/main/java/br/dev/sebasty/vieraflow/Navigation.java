package br.dev.sebasty.vieraflow;

import java.net.URLEncoder;
import java.util.Locale;

final class Navigation {
    static String target(String input) {
        String value = input.trim();
        if (value.isEmpty()) return null;
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) return value;
        if (lower.startsWith("javascript:") || lower.startsWith("data:") || lower.startsWith("file:") || value.contains("://"))
            throw new IllegalArgumentException("Use um endereço HTTP/HTTPS ou digite uma pesquisa.");
        if (!value.matches(".*\\s.*") && value.matches("(?i)(localhost|(?:[a-z0-9-]+\\.)+[a-z0-9-]+)(?::[0-9]+)?(?:[/?#].*)?"))
            return "https://" + value;
        try { return "https://www.google.com/search?q=" + URLEncoder.encode(value, "UTF-8"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }
}
