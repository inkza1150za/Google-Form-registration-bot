package com.example;

import java.util.List;
import org.openqa.selenium.WebElement;

/**
 * One question on a Google Form.
 */
public record FormField(
        int index,
        String title,
        Type type,
        boolean required,
        List<String> options,
        String entryId,
        WebElement container) {

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

    @Override
    public String toString() {
        String head = "[%d] %s%s (%s)".formatted(index, title, required ? " *" : "", type);
        if (options.isEmpty()) {
            return head;
        }
        return head + "\n      ตัวเลือก: " + String.join(" | ", options);
    }
}
