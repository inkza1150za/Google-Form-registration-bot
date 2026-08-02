package com.example;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Remembers a form's questions between runs.
 *
 * <p>A form that has not opened yet shows no questions, so a run that waits for opening has to
 * download and parse the page before it can send anything — a second we cannot spare when the form
 * opens on the dot. Saving the questions from an earlier run lets the next one fire immediately,
 * using the {@code entry.NNN} names it already knows.
 */
final class FormSchema {

    private FormSchema() {
    }

    /** Writes one question per line: {@code index<TAB>entryId<TAB>type<TAB>required<TAB>title}. */
    static void save(Path file, List<FormField> fields) {
        StringBuilder text = new StringBuilder("# โครงฟอร์มที่บันทึกไว้ ลบไฟล์นี้ถ้าฟอร์มเปลี่ยนคำถาม\n");
        for (FormField field : fields) {
            if (field.entryId() == null) {
                continue;
            }
            text.append(field.index()).append('\t')
                    .append(field.entryId()).append('\t')
                    .append(field.type()).append('\t')
                    .append(field.required()).append('\t')
                    .append(field.title().replace('\t', ' ')).append('\n');
        }
        try {
            Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("บันทึกโครงฟอร์มไม่ได้: " + e.getMessage());
        }
    }

    /** Returns an empty list when there is nothing saved yet. */
    static List<FormField> load(Path file) {
        List<FormField> fields = new ArrayList<>();
        if (!Files.exists(file)) {
            return fields;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] cells = line.split("\t", 5);
                if (cells.length < 5) {
                    continue;
                }
                fields.add(new FormField(
                        Integer.parseInt(cells[0]),
                        cells[4],
                        FormField.Type.valueOf(cells[2]),
                        Boolean.parseBoolean(cells[3]),
                        List.of(),
                        cells[1],
                        null));
            }
        } catch (IOException | RuntimeException e) {
            System.out.println("อ่านโครงฟอร์มที่บันทึกไว้ไม่ได้ จะอ่านจากหน้าเว็บแทน: " + e.getMessage());
            return List.of();
        }
        return fields;
    }
}
