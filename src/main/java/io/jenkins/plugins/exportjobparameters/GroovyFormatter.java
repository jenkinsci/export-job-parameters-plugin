package io.jenkins.plugins.exportjobparameters;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.lang.model.SourceVersion;
import org.jenkinsci.plugins.structs.describable.UninstantiatedDescribable;
import org.jenkinsci.plugins.workflow.cps.Snippetizer;

/**
 * Writes values as Pipeline code, the way {@link Snippetizer} does, but laid out and quoted for reading.
 */
final class GroovyFormatter {
    static final String INDENT = "    ";
    private static final int MAX_WIDTH = 100;

    private GroovyFormatter() {}

    /**
     * Writes a value on one line if it fits, otherwise with one argument, entry or element per line.
     *
     * @param indent the indentation of the line the value starts on
     */
    static String format(Object value, String indent) {
        String oneLine = oneLine(value);
        if (indent.length() + oneLine.length() <= MAX_WIDTH && !oneLine.contains("\n")) {
            return oneLine;
        }
        String inner = indent + INDENT;
        if (value instanceof UninstantiatedDescribable ud) {
            Map<String, ?> entries = entries(ud);
            if (entries.size() > 1) {
                return (isCall(ud) ? ud.getSymbol() + "(" : "[") + "\n" + lines(entries, inner) + "\n" + indent
                        + (isCall(ud) ? ")" : "]");
            }
        } else if (value instanceof Map<?, ?> map && map.size() > 1) {
            return "[\n" + lines(map, inner) + "\n" + indent + "]";
        } else if (value instanceof List<?> list && list.size() > 1) {
            return "[\n"
                    + list.stream().map(e -> inner + format(e, inner)).collect(Collectors.joining(",\n"))
                    + "\n" + indent + "]";
        }
        return oneLine;
    }

    private static String lines(Map<?, ?> entries, String indent) {
        return entries.entrySet().stream()
                .map(e -> indent + key(e.getKey()) + ": " + format(e.getValue(), indent))
                .collect(Collectors.joining(",\n"));
    }

    private static String oneLine(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String string) {
            return quote(string);
        }
        if (value instanceof Boolean || value instanceof Number) {
            return value.toString();
        }
        if (value instanceof List<?> list) {
            return "[" + list.stream().map(GroovyFormatter::oneLine).collect(Collectors.joining(", ")) + "]";
        }
        if (value instanceof Map<?, ?> map) {
            return map.isEmpty() ? "[:]" : "[" + oneLine(map) + "]";
        }
        if (value instanceof UninstantiatedDescribable ud) {
            return isCall(ud) ? ud.getSymbol() + "(" + oneLine(entries(ud)) + ")" : "[" + oneLine(entries(ud)) + "]";
        }
        return Snippetizer.object2Groovy(value, true);
    }

    private static String oneLine(Map<?, ?> entries) {
        return entries.entrySet().stream()
                .map(e -> key(e.getKey()) + ": " + oneLine(e.getValue()))
                .collect(Collectors.joining(", "));
    }

    /**
     * @return true to write {@code symbol(...)}, false to write {@code [$class: ...]}
     */
    static boolean isCall(UninstantiatedDescribable ud) {
        // Snippetizer knows when a symbol cannot be used, such as when a step has the same name
        return ud.getSymbol() != null && !Snippetizer.object2Groovy(ud, true).startsWith("[");
    }

    private static Map<String, ?> entries(UninstantiatedDescribable ud) {
        if (isCall(ud) || ud.getKlass() == null) {
            return ud.getArguments();
        }
        Map<String, Object> entries = new LinkedHashMap<>();
        entries.put("$class", ud.getKlass());
        entries.putAll(ud.getArguments());
        return entries;
    }

    private static String key(Object key) {
        return key instanceof String name && SourceVersion.isName(name) ? name : oneLine(key);
    }

    /**
     * Prefers the quotes that need no escaping. Never uses a form in which Groovy would interpolate a {@code $}.
     */
    static String quote(String text) {
        String escaped = text.replace("\\", "\\\\");
        if (text.contains("\n")) {
            escaped = escaped.replace("'''", "\\'\\'\\'");
            if (escaped.endsWith("'")) {
                escaped = escaped.substring(0, escaped.length() - 1) + "\\'";
            }
            return "'''" + escaped + "'''";
        }
        escaped = escaped.replace("\r", "\\r");
        if (text.contains("'") && !text.contains("\"") && !text.contains("$")) {
            return '"' + escaped + '"';
        }
        return "'" + escaped.replace("'", "\\'") + "'";
    }
}
