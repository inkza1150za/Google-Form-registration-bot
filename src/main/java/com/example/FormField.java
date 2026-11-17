package com.example;

import java.util.List;
import org.openqa.selenium.WebElement;

/**
 * One question on a Google Form.
 *
 * @param page           which section of a multi-page form this sits on, counting from 0
 * @param optionTargets  for each entry in {@code options}, the page that choosing it leads to:
 *                       a page number, {@link #CONTINUE} for "just carry on", or {@link #SUBMIT}
 */
public record FormField(
        int index,
        String title,
        Type type,
        boolean required,
        List<String> options,
        List<Integer> optionTargets,
        int page,
        String entryId,
        WebElement container) {

    /** Choosing this option moves to the next section in order. */
    public static final int CONTINUE = -1;

    /** Choosing this option ends the form there and then. */
    public static final int SUBMIT = -2;

    public enum Type {
        SHORT_TEXT,
        PARAGRAPH,
        RADIO,
        CHECKBOX,
        DROPDOWN,
        DATE,
        TIME,
        UNKNOWN
    }

    /** Convenience for the browser path, which never deals with sections. */
    public FormField(int index, String title, Type type, boolean required,
            List<String> options, String entryId, WebElement container) {
        this(index, title, type, required, options, List.of(), 0, entryId, container);
    }

    /** Where answering {@code choice} leads, or {@link #CONTINUE} when it does not redirect. */
    public int targetOf(String choice) {
        int position = options.indexOf(choice);
        if (position < 0 || position >= optionTargets.size()) {
            return CONTINUE;
        }
        return optionTargets.get(position);
    }

    @Override
    public String toString() {
        String head = "[%d] %s%s (%s)".formatted(index, title, required ? " *" : "", type);
        if (options.isEmpty()) {
            return head;
        }
        return head + "\n      ตัวเลือก: " + String.join(" | ", options);
    }
}
