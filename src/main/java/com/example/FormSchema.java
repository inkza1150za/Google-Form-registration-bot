package com.example;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Remembers a form's questions between runs.
 *
 * <p>A form that has not opened yet shows nothing to read, so a run that waits for opening would
 * have to download and parse the page before it could send anything — a second we cannot spare
 * when the form opens on the dot. Saving the questions from an earlier run lets the next one fire
 * immediately, using the {@code entry.NNN} names and section layout it already knows.
 */
final class FormSchema {

    private static final Pattern FORM_ID = Pattern.compile("/forms/d/e/([^/]+)");
    private static final String OWNER_PREFIX = "# form: ";

    private FormSchema() {
    }

    /**
     * The id in the form's own URL. Two forms can look identical question for question and still
     * have completely different {@code entry.NNN} names, so a cache must never be shared between
     * them — the file name keeps them apart on its own.
     */
    static String idOf(String formUrl) {
        Matcher matcher = FORM_ID.matcher(formUrl);
        return matcher.find() ? matcher.group(1) : "unknown";
    }

    /** Where a form's questions are cached when {@code -Dschema} is not given. */
    static Path defaultPathFor(String formUrl) {
        return Path.of("schema-" + idOf(formUrl) + ".tsv");
    }

    /** One question per line: {@code index entryId type required page title options}, tab separated. */
    static void save(Path file, String formUrl, List<FormField> fields) {
        StringBuilder text = new StringBuilder("# โครงฟอร์มที่บันทึกไว้ ลบไฟล์นี้ถ้าฟอร์มเปลี่ยนคำถาม\n")
                .append(OWNER_PREFIX).append(idOf(formUrl)).append('\n');
        for (FormField field : fields) {
            if (field.entryId() == null) {
                continue;
            }
            text.append(field.index()).append('\t')
                    .append(field.entryId()).append('\t')
                    .append(field.type()).append('\t')
                    .append(field.required()).append('\t')
                    .append(field.page()).append('\t')
                    .append(clean(field.title())).append('\t')
                    .append(encodeOptions(field)).append('\n');
        }
        try {
            Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("บันทึกโครงฟอร์มไม่ได้: " + e.getMessage());
        }
    }

    /**
     * Returns an empty list when there is nothing saved yet, or when what is saved belongs to a
     * different form — using another form's entry names would send every answer into the void.
     */
    static List<FormField> load(Path file, String formUrl) {
        List<FormField> fields = new ArrayList<>();
        if (!Files.exists(file)) {
            return fields;
        }
        boolean ownerConfirmed = false;
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.replace("﻿", "");
                if (line.startsWith(OWNER_PREFIX)) {
                    String owner = line.substring(OWNER_PREFIX.length()).trim();
                    if (!owner.equals(idOf(formUrl))) {
                        System.out.println("ไฟล์ " + file + " เป็นโครงของฟอร์มอื่น (" + owner + ") จะไม่ใช้");
                        return List.of();
                    }
                    ownerConfirmed = true;
                    continue;
                }
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] cells = line.split("\t", 7);
                if (cells.length < 6) {
                    continue;
                }
                List<String> options = new ArrayList<>();
                List<Integer> targets = new ArrayList<>();
                if (cells.length == 7) {
                    decodeOptions(cells[6], options, targets);
                }
                fields.add(new FormField(
                        Integer.parseInt(cells[0]),
                        cells[5],
                        FormField.Type.valueOf(cells[2]),
                        Boolean.parseBoolean(cells[3]),
                        List.copyOf(options),
                        List.copyOf(targets),
                        Integer.parseInt(cells[4]),
                        cells[1],
                        null));
            }
            if (!ownerConfirmed && !fields.isEmpty()) {
                // No "# form:" line, so there is no way to tell which form these belong to.
                // Guessing would post one form's answers into another's entry names.
                System.out.println("ไฟล์ " + file + " ไม่ได้ระบุว่าเป็นโครงของฟอร์มไหน จะไม่ใช้");
                System.out.println("  เก็บใหม่ด้วยการรันตอนฟอร์มเปิด แล้วบอทจะเขียนไฟล์ให้เอง");
                return List.of();
            }
        } catch (IOException | RuntimeException e) {
            System.out.println("อ่านโครงฟอร์มที่บันทึกไว้ไม่ได้ จะอ่านจากหน้าเว็บแทน: " + e.getMessage());
            return List.of();
        }
        return fields;
    }

    /** {@code ตัวเลือก>หน้าปลายทาง|ตัวเลือก>หน้าปลายทาง} */
    private static String encodeOptions(FormField field) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < field.options().size(); i++) {
            if (i > 0) {
                out.append('|');
            }
            int target = i < field.optionTargets().size() ? field.optionTargets().get(i) : FormField.CONTINUE;
            out.append(clean(field.options().get(i))).append('>').append(target);
        }
        return out.toString();
    }

    private static void decodeOptions(String encoded, List<String> options, List<Integer> targets) {
        if (encoded.isBlank()) {
            return;
        }
        for (String pair : encoded.split("\\|")) {
            int split = pair.lastIndexOf('>');
            if (split < 0) {
                continue;
            }
            options.add(pair.substring(0, split));
            try {
                targets.add(Integer.parseInt(pair.substring(split + 1).trim()));
            } catch (NumberFormatException e) {
                targets.add(FormField.CONTINUE);
            }
        }
    }

    /** Tabs, newlines and pipes would break the line format they are stored in. */
    private static String clean(String text) {
        return text == null ? "" : text.replaceAll("[\t\n\r|]", " ");
    }
}
