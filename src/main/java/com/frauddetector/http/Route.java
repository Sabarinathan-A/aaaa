package com.frauddetector.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A single registered route: an HTTP method, a path template that may contain
 * {@code {param}} placeholders (e.g. {@code /api/claims/{id}}), and the handler
 * to invoke. Compiles the template into a regex so paths can be matched and
 * named parameters extracted.
 */
public final class Route {

    /** Handler contract for a route. Returning a value is optional; a handler
     *  may instead call {@link HttpContext#respond(int, Object)} directly. */
    @FunctionalInterface
    public interface Handler {
        Object handle(HttpContext ctx) throws Exception;
    }

    private static final Pattern PARAM = Pattern.compile("\\{([a-zA-Z_][a-zA-Z0-9_]*)}");

    private final String method;
    private final String template;
    private final Handler handler;
    private final Pattern pattern;
    private final List<String> paramNames = new ArrayList<>();

    public Route(String method, String template, Handler handler) {
        this.method = method.toUpperCase();
        this.template = template;
        this.handler = handler;
        this.pattern = compile(template);
    }

    private Pattern compile(String tmpl) {
        StringBuilder regex = new StringBuilder("^");
        Matcher m = PARAM.matcher(tmpl);
        int last = 0;
        while (m.find()) {
            regex.append(Pattern.quote(tmpl.substring(last, m.start())));
            paramNames.add(m.group(1));
            regex.append("([^/]+)");
            last = m.end();
        }
        regex.append(Pattern.quote(tmpl.substring(last)));
        regex.append("$");
        return Pattern.compile(regex.toString());
    }

    public String method() {
        return method;
    }

    public String template() {
        return template;
    }

    public Handler handler() {
        return handler;
    }

    /** True if the given concrete path matches this route's template (ignoring method). */
    public boolean matchesPath(String path) {
        return pattern.matcher(path).matches();
    }

    /** Extract named path parameters from the given path (assumes it matches). */
    public Map<String, String> extractParams(String path) {
        Map<String, String> params = new LinkedHashMap<>();
        Matcher m = pattern.matcher(path);
        if (m.matches()) {
            for (int i = 0; i < paramNames.size(); i++) {
                params.put(paramNames.get(i), m.group(i + 1));
            }
        }
        return params;
    }
}
