package com.example;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Entry point. Reads a Google Form, fills it from an answers file, and optionally submits.
 *
 * <pre>
 *   mvn -q compile exec:java "-Dform.url=https://docs.google.com/forms/d/e/XXXX/viewform"
 *   mvn -q compile exec:java "-Dform.url=..." "-Dheadless=false"
 *   mvn -q compile exec:java "-Dform.url=..." "-Dsubmit=true"
 * </pre>
 */
public class App {

    public static void main(String[] args) {
        String url = System.getProperty("form.url", args.length > 0 ? args[0] : "");
        if (url.isBlank()) {
            printUsage();
            return;
        }

        boolean headless = !"false".equalsIgnoreCase(System.getProperty("headless", "true"));
        boolean submit = "true".equalsIgnoreCase(System.getProperty("submit", "false"));
        Path answersFile = Path.of(System.getProperty("answers", "answers.txt"));

        Map<String, String> answers = readAnswers(answersFile);

        try (GoogleFormBot bot = new GoogleFormBot(headless)) {
            System.out.println("กำลังเปิดฟอร์ม...");
            String title;
            try {
                title = bot.open(url);
            } catch (IllegalStateException e) {
                System.out.println(e.getMessage());
                return;
            }
            System.out.println("ฟอร์ม: " + title);
            System.out.println();

            List<FormField> fields = bot.readFields();
            System.out.println("พบคำถาม " + fields.size() + " ข้อ");
            fields.forEach(field -> System.out.println("  " + field));
            System.out.println();

            if (answers.isEmpty()) {
                System.out.println("ยังไม่มีไฟล์คำตอบ (" + answersFile + ") — อ่านอย่างเดียว ไม่ได้กรอกอะไร");
                System.out.println("สร้างไฟล์นั้นในรูปแบบ  ชื่อคำถาม = คำตอบ  แล้วรันใหม่เพื่อให้บอทกรอกให้");
                return;
            }

            int filled = fill(bot, fields, answers);
            System.out.println();

            if (filled == 0) {
                System.out.println("ไม่ได้กรอกอะไรเลย — ชื่อคำถามในไฟล์คำตอบไม่ตรงกับในฟอร์ม");
                return;
            }
            if (!submit) {
                System.out.println("กรอกครบแล้วแต่ยังไม่ส่ง (ใส่ \"-Dsubmit=true\" ถ้าต้องการส่งจริง)");
                return;
            }
            bot.submit();
            System.out.println("ส่งฟอร์มเรียบร้อย");
        }
    }

    /** Fills every field that has a matching answer. Returns how many were filled. */
    private static int fill(GoogleFormBot bot, List<FormField> fields, Map<String, String> answers) {
        int filled = 0;
        for (FormField field : fields) {
            String answer = matchAnswer(field.title(), answers);
            if (answer == null) {
                System.out.println((field.required() ? "ข้าม (จำเป็นต้องตอบ!): " : "ข้าม: ") + field.title());
                continue;
            }
            try {
                bot.fill(field, answer);
                System.out.println("กรอกแล้ว: " + field.title() + " = " + answer);
                filled++;
            } catch (RuntimeException e) {
                System.out.println("กรอกไม่ได้: " + e.getMessage());
            }
        }
        return filled;
    }

    /** Exact title match first, then a case-insensitive "contains" fallback. */
    private static String matchAnswer(String title, Map<String, String> answers) {
        String exact = answers.get(title);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, String> entry : answers.entrySet()) {
            if (title.toLowerCase().contains(entry.getKey().toLowerCase())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Reads "question = answer" lines. Blank lines and lines starting with # are ignored.
     * Returns an empty map if the file does not exist.
     */
    private static Map<String, String> readAnswers(Path file) {
        Map<String, String> answers = new LinkedHashMap<>();
        if (!Files.exists(file)) {
            return answers;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int separator = trimmed.indexOf('=');
                if (separator < 0) {
                    System.out.println("ข้ามบรรทัดที่ไม่มี '=' : " + trimmed);
                    continue;
                }
                answers.put(trimmed.substring(0, separator).trim(), trimmed.substring(separator + 1).trim());
            }
        } catch (IOException e) {
            throw new IllegalStateException("อ่านไฟล์คำตอบไม่ได้: " + file, e);
        }
        return answers;
    }

    private static void printUsage() {
        System.out.println("""
                ต้องระบุ URL ของฟอร์มก่อน

                  mvn -q compile exec:java "-Dform.url=https://docs.google.com/forms/d/e/XXXX/viewform"

                ตัวเลือกเพิ่มเติม
                  -Dheadless=false   เปิด Chrome ให้เห็นหน้าจอ (ปกติรันแบบซ่อน)
                  -Dsubmit=true      กดส่งฟอร์มจริง (ปกติกรอกอย่างเดียว ไม่ส่ง)
                  -Danswers=<path>   ไฟล์คำตอบ (ปกติคือ answers.txt)
                """);
    }
}
