package com.alber.backend.chat;

import com.jayway.jsonpath.JsonPath;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/** Parses a server-sent event stream as written by Spring MVC, for assertions. */
final class SseEvents {

    /** One event: its name ({@code event:} line) and its data ({@code data:} lines, joined). */
    record Event(String name, String data) {

        /** A string field of the JSON data. */
        String field(String name) {
            return JsonPath.read(data, "$." + name);
        }
    }

    private SseEvents() {}

    static List<Event> parse(String body) {
        return Arrays.stream(body.split("\n\n"))
                .filter(block -> !block.isBlank())
                .map(SseEvents::event)
                .toList();
    }

    /** The joined {@code text} of all {@code token} events. */
    static String joinedTokenText(List<Event> events) {
        return events.stream()
                .filter(e -> e.name().equals("token"))
                .map(e -> e.field("text"))
                .collect(Collectors.joining());
    }

    static List<String> names(List<Event> events) {
        return events.stream().map(Event::name).toList();
    }

    private static Event event(String block) {
        String name = null;
        StringBuilder data = new StringBuilder();
        for (String line : block.split("\n", -1)) {
            if (line.startsWith("event:")) {
                name = line.substring("event:".length()).strip();
            } else if (line.startsWith("data:")) {
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                String value = line.substring("data:".length());
                data.append(value.startsWith(" ") ? value.substring(1) : value);
            }
        }
        return new Event(name, data.toString());
    }
}
