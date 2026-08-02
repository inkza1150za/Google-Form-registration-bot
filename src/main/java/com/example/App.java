package com.example;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Entry point. Reads a Google Form, fills it in, and optionally submits.
 *
 * <p>One person at a time from an answers file:
 * <pre>
 *   mvn -q compile exec:java "-Dform.url=..." "-Danswers=answers.txt"
 * </pre>
 *
 * <p>Or many people in one run from a CSV:
 * <pre>
 *   mvn -q compile exec:java "-Dform.url=..." "-Dpeople=people.csv" "-Dsubmit=true"
 * </pre>
 */
public class App {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Upper bound on parallel submissions. */
    private static final int MAX_THREADS = 16;

    public static void main(String[] args) {
        useUtf8Console();

        String url = System.getProperty("form.url", args.length > 0 ? args[0] : "");
        if (url.isBlank()) {
            printUsage();
            return;
        }

        boolean headless = !"false".equalsIgnoreCase(System.getProperty("headless", "true"));
        boolean submit = "true".equalsIgnoreCase(System.getProperty("submit", "false"));
        Path answersFile = Path.of(System.getProperty("answers", "answers.txt"));
        String peopleProperty = System.getProperty("people", "");
        Path resultsFile = Path.of(System.getProperty("results", "results.csv"));
        long waitSeconds = seconds("wait", 0);
        long pollSeconds = seconds("poll", 3);
        long delaySeconds = seconds("delay", 5);

        List<Map<String, String>> people = peopleProperty.isBlank()
                ? List.of()
                : Csv.read(Path.of(peopleProperty));
        if (!peopleProperty.isBlank() && people.isEmpty()) {
            System.out.println("ไฟล์ " + peopleProperty + " ไม่มีข้อมูลสักแถว (ต้องมีบรรทัดหัวตาราง + อย่างน้อย 1 แถว)");
            return;
        }

        if ("http".equalsIgnoreCase(System.getProperty("mode", "browser"))) {
            runHttpMode(url, people, answersFile, submit, delaySeconds, waitSeconds, pollSeconds, resultsFile);
            return;
        }

        try (GoogleFormBot bot = new GoogleFormBot(headless)) {
            String title;
            try {
                if (waitSeconds > 0) {
                    System.out.println("รอฟอร์มเปิด (รอไม่เกิน " + waitSeconds + " วิ, เช็กทุก " + pollSeconds + " วิ)");
                    title = bot.waitUntilOpen(url, Duration.ofSeconds(pollSeconds), Duration.ofSeconds(waitSeconds));
                    System.out.println("ฟอร์มเปิดแล้ว");
                } else {
                    System.out.println("กำลังเปิดฟอร์ม...");
                    title = bot.open(url);
                }
            } catch (FormClosedException | IllegalStateException e) {
                System.out.println(e.getMessage());
                return;
            }
            System.out.println("ฟอร์ม: " + title);
            System.out.println();

            List<FormField> fields = bot.readFields();
            printFields(fields);
            warnAboutDuplicateTitles(fields);

            if (!people.isEmpty()) {
                runBatch(bot, url, fields, people, submit, delaySeconds, resultsFile);
            } else {
                runSingle(bot, fields, answersFile, submit);
            }
        }
    }

    /**
     * Browserless path: reads the questions straight out of the form's HTML and posts each
     * response. No Chrome to start and no typing, so it runs several times faster.
     */
    private static void runHttpMode(String url, List<Map<String, String>> people, Path answersFile,
            boolean submit, long delaySeconds, long waitSeconds, long pollSeconds, Path resultsFile) {

        HttpFormClient form = new HttpFormClient(url);
        Path schemaFile = Path.of(System.getProperty("schema", "schema.tsv"));

        List<FormField> fields;
        try {
            fields = form.readFields();
            FormSchema.save(schemaFile, fields);
        } catch (FormClosedException closed) {
            // Nothing to read while it is closed — fall back to the questions we saw last time,
            // which is what lets us fire the instant it opens instead of downloading the page first
            fields = FormSchema.load(schemaFile);
            if (!fields.isEmpty()) {
                System.out.println("ฟอร์มยังปิด แต่มีโครงที่บันทึกไว้ (" + schemaFile + ") — พร้อมยิงทันทีที่เปิด");
            } else if (waitSeconds > 0) {
                System.out.println("รอฟอร์มเปิด (รอไม่เกิน " + waitSeconds + " วิ, เช็กทุก " + pollSeconds + " วิ)");
                try {
                    fields = form.waitUntilOpen(Duration.ofSeconds(pollSeconds), Duration.ofSeconds(waitSeconds));
                } catch (FormClosedException | IllegalStateException e) {
                    System.out.println(e.getMessage());
                    return;
                }
                FormSchema.save(schemaFile, fields);
                System.out.println("ฟอร์มเปิดแล้ว");
            } else {
                System.out.println(closed.getMessage());
                return;
            }
        } catch (IllegalStateException e) {
            System.out.println(e.getMessage());
            return;
        }

        if (fields.isEmpty()) {
            System.out.println("อ่านคำถามจากหน้าเว็บไม่ได้ — ลองรันโหมดปกติ (ตัด \"-Dmode=http\" ออก) ดูว่าเห็นคำถามไหม");
            return;
        }
        List<FormField> questions = fields;
        printFields(questions);
        warnAboutDuplicateTitles(questions);

        List<Map<String, String>> rows = people;
        boolean fromCsv = !people.isEmpty();
        if (!fromCsv) {
            Map<String, String> answers = readAnswers(answersFile);
            if (answers.isEmpty()) {
                System.out.println("ยังไม่มีไฟล์คำตอบ (" + answersFile + ") — อ่านอย่างเดียว ไม่ได้ส่งอะไร");
                return;
            }
            rows = List.of(answers);
        }

        printMatches(questions, rows.get(0));

        Set<Integer> alreadySent = fromCsv ? readSentRows(resultsFile) : Set.of();
        if (!alreadySent.isEmpty()) {
            System.out.println("มี " + alreadySent.size() + " แถวที่ส่งไปแล้วตาม " + resultsFile + " — จะข้ามให้");
        }
        if (!submit) {
            System.out.println("โหมดซ้อม: จะเตรียมคำตอบให้ครบแต่ไม่ส่ง (ใส่ \"-Dsubmit=true\" ถ้าต้องการส่งจริง)");
        }
        System.out.println();

        int threads = threadCount();
        if (threads > 1) {
            System.out.println("ยิงพร้อมกัน " + threads + " สาย — ลำดับบรรทัดที่พิมพ์ออกมาจะสลับกันได้");
            System.out.println();
        }

        waitUntilStartTime();
        Instant openingWindow = Instant.now().plusSeconds(Math.max(waitSeconds, 0));

        AtomicInteger sent = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        int skipped = 0;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < rows.size(); i++) {
                int row = i + 1;
                Map<String, String> person = rows.get(i);
                String label = fromCsv ? "[" + row + "/" + rows.size() + "] " + describe(person) : describe(person);

                if (alreadySent.contains(row)) {
                    System.out.println(label + " — ส่งไปแล้ว ข้าม");
                    skipped++;
                    continue;
                }

                pool.execute(() -> sendOne(form, questions, person, label, row, submit, fromCsv,
                        resultsFile, sent, failed, openingWindow));

                if (i < rows.size() - 1 && delaySeconds > 0) {
                    sleep(delaySeconds);
                }
            }
        } finally {
            pool.shutdown();
            try {
                pool.awaitTermination(1, TimeUnit.HOURS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pool.shutdownNow();
            }
        }

        if (fromCsv) {
            System.out.println();
            System.out.println("สรุป: สำเร็จ " + sent.get() + " | พลาด " + failed.get() + " | ข้าม " + skipped
                    + " | ทั้งหมด " + rows.size());
        }
    }

    /**
     * Sends one person's answers. Runs on a worker thread when several are sent at once, so it
     * builds each line of output in full before printing — otherwise the lines interleave mid-word.
     */
    private static void sendOne(HttpFormClient form, List<FormField> fields, Map<String, String> person,
            String label, int row, boolean submit, boolean fromCsv, Path resultsFile,
            AtomicInteger sent, AtomicInteger failed, Instant openingWindow) {
        try {
            Map<String, String> byEntryId = entryValues(fields, person);
            if (byEntryId.isEmpty()) {
                throw new IllegalStateException("คำตอบไม่ตรงกับคำถามในฟอร์มสักช่อง");
            }
            if (submit) {
                int attempts = submitWithRetry(form, byEntryId, label, openingWindow);
                if (fromCsv) {
                    record(resultsFile, row, "ส่งแล้ว", describe(person));
                }
                System.out.println(label + " — ส่งแล้ว" + (attempts > 1 ? " (ลอง " + attempts + " ครั้ง)" : ""));
            } else {
                System.out.println(label + " — เตรียมครบ " + byEntryId.size() + " ช่อง (ยังไม่ส่ง)");
            }
            sent.incrementAndGet();
        } catch (RuntimeException e) {
            failed.incrementAndGet();
            System.out.println(label + " — พลาด: " + e.getMessage());
            if (fromCsv) {
                record(resultsFile, row, "พลาด", e.getMessage());
            }
        }
    }

    /**
     * Posts one response, retrying on failure. A form that has just switched from closed to open
     * can reject the first attempts for a moment, which is what makes an unattended run look like
     * it worked for some people and not others.
     *
     * @return how many attempts it took
     */
    private static int submitWithRetry(HttpFormClient form, Map<String, String> byEntryId, String label,
            Instant openingWindow) {
        int maxAttempts = (int) Math.max(1, seconds("retry", 3));
        RuntimeException lastFailure = null;
        for (int attempt = 1; ; attempt++) {
            try {
                form.submit(byEntryId);
                return attempt;
            } catch (RuntimeException e) {
                lastFailure = e;
                if (Instant.now().isBefore(openingWindow)) {
                    // Still inside the window we were told to wait: the form has probably not
                    // started accepting yet, so keep knocking instead of giving up
                    sleepMillis(250);
                    continue;
                }
                if (attempt >= maxAttempts) {
                    throw lastFailure;
                }
                System.out.println(label + " — ครั้งที่ " + attempt + " ไม่ผ่าน (" + e.getMessage() + ") ลองใหม่");
                sleepMillis(1000);
            }
        }
    }

    /** Sleeps until the wall clock reaches {@code -Dstart}, so the first request goes out on time. */
    private static void waitUntilStartTime() {
        String startAt = System.getProperty("start", "").trim();
        if (startAt.isEmpty()) {
            return;
        }
        LocalTime target;
        try {
            target = LocalTime.parse(startAt);
        } catch (RuntimeException e) {
            System.out.println("ค่า -Dstart=" + startAt + " อ่านไม่ออก ต้องเป็นรูปแบบ HH:mm หรือ HH:mm:ss — จะเริ่มทันที");
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime fireAt = now.toLocalDate().atTime(target);
        if (!fireAt.isAfter(now)) {
            fireAt = fireAt.plusDays(1);
        }
        Duration until = Duration.between(now, fireAt);
        System.out.println("รอถึงเวลา " + target + " (อีก " + until.toSeconds() + " วินาที)");
        sleepMillis(until.toMillis());
        System.out.println("ถึงเวลาแล้ว ยิงเลย");
    }

    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ถูกสั่งหยุดระหว่างรอ", e);
        }
    }

    /** How many responses to send at the same time. Capped so a typo cannot open hundreds of them. */
    private static int threadCount() {
        long requested = seconds("threads", 1);
        if (requested < 1) {
            return 1;
        }
        if (requested > MAX_THREADS) {
            System.out.println("จำกัดจำนวนสายไว้ที่ " + MAX_THREADS + " (ขอมา " + requested + ")");
            return MAX_THREADS;
        }
        return (int) requested;
    }

    private static void printFields(List<FormField> fields) {
        System.out.println("พบคำถาม " + fields.size() + " ข้อ");
        fields.forEach(field -> System.out.println("  " + field));
        System.out.println();
    }

    /** Fills the form once from an answers file. */
    private static void runSingle(GoogleFormBot bot, List<FormField> fields, Path answersFile, boolean submit) {
        Map<String, String> answers = readAnswers(answersFile);
        if (answers.isEmpty()) {
            System.out.println("ยังไม่มีไฟล์คำตอบ (" + answersFile + ") — อ่านอย่างเดียว ไม่ได้กรอกอะไร");
            System.out.println("สร้างไฟล์นั้นในรูปแบบ  ชื่อคำถาม = คำตอบ  แล้วรันใหม่เพื่อให้บอทกรอกให้");
            return;
        }

        printMatches(fields, answers);
        int filled = fill(bot, fields, answers, true);
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

    /**
     * Fills and submits the form once per CSV row, reloading it in between.
     * Rows already recorded as sent in the results file are skipped, so an interrupted run
     * can be restarted without sending anyone twice.
     */
    private static void runBatch(GoogleFormBot bot, String url, List<FormField> fields,
            List<Map<String, String>> people, boolean submit, long delaySeconds, Path resultsFile) {

        Set<Integer> alreadySent = readSentRows(resultsFile);
        if (!alreadySent.isEmpty()) {
            System.out.println("มี " + alreadySent.size() + " แถวที่ส่งไปแล้วตาม " + resultsFile + " — จะข้ามให้");
        }
        if (!submit) {
            System.out.println("โหมดซ้อม: จะกรอกให้ครบทุกแถวแต่ไม่ส่ง (ใส่ \"-Dsubmit=true\" ถ้าต้องการส่งจริง)");
        }
        System.out.println();

        int sent = 0;
        int failed = 0;
        int skipped = 0;
        for (int i = 0; i < people.size(); i++) {
            int row = i + 1;
            Map<String, String> person = people.get(i);
            String label = "[" + row + "/" + people.size() + "] " + describe(person);

            if (alreadySent.contains(row)) {
                System.out.println(label + " — ส่งไปแล้ว ข้าม");
                skipped++;
                continue;
            }

            System.out.println(label);
            try {
                Map<String, String> byEntryId = entryValues(fields, person);
                if (byEntryId.isEmpty()) {
                    throw new IllegalStateException("หัวตารางใน CSV ไม่ตรงกับคำถามในฟอร์มสักช่อง");
                }
                // Answers ride along in the URL, so the page opens already filled in
                bot.open(GoogleFormBot.prefilledUrl(url, byEntryId));
                if (submit) {
                    bot.submit();
                    record(resultsFile, row, "ส่งแล้ว", describe(person));
                    System.out.println("   ส่งแล้ว");
                } else {
                    System.out.println("   กรอกครบ " + byEntryId.size() + " ช่อง (ยังไม่ส่ง)");
                }
                sent++;
            } catch (RuntimeException e) {
                failed++;
                System.out.println("   พลาด: " + e.getMessage());
                record(resultsFile, row, "พลาด", e.getMessage());
            }

            if (i < people.size() - 1 && delaySeconds > 0) {
                sleep(delaySeconds);
            }
        }

        System.out.println();
        System.out.println("สรุป: สำเร็จ " + sent + " | พลาด " + failed + " | ข้าม " + skipped
                + " | ทั้งหมด " + people.size());
        if (failed > 0) {
            System.out.println("แถวที่พลาดยังไม่ถูกบันทึกว่าส่งแล้ว รันคำสั่งเดิมซ้ำได้เลย จะลองเฉพาะแถวที่ยังไม่สำเร็จ");
        }
    }

    /**
     * Maps each answered question to its {@code entry.NNN} name so the answers can travel in the
     * URL instead of being typed one keystroke at a time.
     */
    private static Map<String, String> entryValues(List<FormField> fields, Map<String, String> answers) {
        Map<Integer, String> keyByIndex = matchKeys(fields, answers.keySet());
        Map<String, String> values = new LinkedHashMap<>();
        for (FormField field : fields) {
            String key = keyByIndex.get(field.index());
            String answer = key == null ? null : answers.get(key);
            if (answer == null) {
                if (field.required()) {
                    System.out.println("   ไม่มีคำตอบให้ข้อที่จำเป็น: " + field.title());
                }
                continue;
            }
            if (field.entryId() == null) {
                System.out.println("   ข้อนี้ใส่ในลิงก์ไม่ได้ ข้ามไป: " + field.title());
                continue;
            }
            values.put(field.entryId(), answer);
        }
        return values;
    }

    /** Fills every field that has a matching answer. Returns how many were filled. */
    private static int fill(GoogleFormBot bot, List<FormField> fields, Map<String, String> answers, boolean verbose) {
        Map<Integer, String> keyByIndex = matchKeys(fields, answers.keySet());
        int filled = 0;
        for (FormField field : fields) {
            String key = keyByIndex.get(field.index());
            String answer = key == null ? null : answers.get(key);
            if (answer == null) {
                if (verbose) {
                    System.out.println((field.required() ? "ข้าม (จำเป็นต้องตอบ!): " : "ข้าม: ") + field.title());
                } else if (field.required()) {
                    System.out.println("   ไม่มีคำตอบให้ข้อที่จำเป็น: " + field.title());
                }
                continue;
            }
            try {
                bot.fill(field, answer);
                if (verbose) {
                    System.out.println("กรอกแล้ว: " + field.title() + " = " + answer);
                }
                filled++;
            } catch (RuntimeException e) {
                System.out.println((verbose ? "กรอกไม่ได้: " : "   กรอกไม่ได้: ") + e.getMessage());
            }
        }
        return filled;
    }

    /**
     * Decides which answer key belongs to which question.
     *
     * <p>Form questions are rarely worded like our column headers — "เลขที่" has to find
     * "เลขที่ผู้สมัคร (9 หลัก)". So every question is scored against every key and the strongest
     * pairs are taken first, each key being spent only once. Scoring the whole set instead of
     * taking the first partial hit is what stops a short key like "ชื่อ" from claiming a question
     * that a longer key fits better.
     *
     * @return the chosen key for each question, by question index
     */
    private static Map<Integer, String> matchKeys(List<FormField> fields, Set<String> keys) {
        Map<Integer, String> chosen = new LinkedHashMap<>();
        Set<String> spent = new HashSet<>();

        // "[2] = ..." names a question outright and always wins
        for (FormField field : fields) {
            String indexKey = "[" + field.index() + "]";
            if (keys.contains(indexKey)) {
                chosen.put(field.index(), indexKey);
                spent.add(indexKey);
            }
        }

        List<int[]> ranked = new ArrayList<>();
        List<String> keyList = new ArrayList<>(keys);
        for (FormField field : fields) {
            if (chosen.containsKey(field.index())) {
                continue;
            }
            for (int k = 0; k < keyList.size(); k++) {
                String key = keyList.get(k);
                if (key.startsWith("[")) {
                    continue;
                }
                int score = similarity(field.title(), key);
                if (score > 0) {
                    ranked.add(new int[] {score, field.index(), k});
                }
            }
        }
        ranked.sort((a, b) -> Integer.compare(b[0], a[0]));

        for (int[] pair : ranked) {
            String key = keyList.get(pair[2]);
            if (chosen.containsKey(pair[1]) || spent.contains(key)) {
                continue;
            }
            chosen.put(pair[1], key);
            spent.add(key);
        }
        return chosen;
    }

    /** Higher means a better fit; 0 means the two are unrelated. */
    private static int similarity(String title, String key) {
        String t = normalize(title);
        String k = normalize(key);
        if (t.isEmpty() || k.isEmpty()) {
            return 0;
        }
        // a longer key that still fits is more specific, so it earns more
        if (t.equals(k)) {
            return 10_000 + k.length();
        }
        if (t.startsWith(k)) {
            return 8_000 + k.length();
        }
        if (t.contains(k)) {
            return 6_000 + k.length();
        }
        if (k.contains(t)) {
            return 4_000 + t.length();
        }
        return 0;
    }

    /** Drops the parts that differ between a column header and the question it belongs to. */
    private static String normalize(String text) {
        return text.toLowerCase()
                .replaceAll("\\([^)]*\\)", "")   // "(9 หลัก)", "(ตรงกับในระบบ SC-Market)"
                .replaceAll("[\\s\\-_.:*/,]", "")
                .trim();
    }

    /** Shows which column ended up feeding which question, so a wrong guess is caught before sending. */
    private static void printMatches(List<FormField> fields, Map<String, String> answers) {
        Map<Integer, String> keyByIndex = matchKeys(fields, answers.keySet());
        System.out.println("จับคู่คำถามกับข้อมูลได้แบบนี้");
        for (FormField field : fields) {
            String key = keyByIndex.get(field.index());
            if (key != null) {
                System.out.println("  " + field.title() + "  ←  " + key);
            } else {
                System.out.println("  " + field.title() + "  ←  (ไม่มีข้อมูลให้)"
                        + (field.required() ? "  ** ข้อนี้บังคับตอบ **" : ""));
            }
        }
        List<String> unused = new ArrayList<>();
        for (String key : answers.keySet()) {
            if (!keyByIndex.containsValue(key)) {
                unused.add(key);
            }
        }
        if (!unused.isEmpty()) {
            System.out.println("  ไม่ได้ใช้: " + String.join(", ", unused));
        }
        System.out.println();
    }

    /** Questions sharing a title cannot be told apart by name, so point the user at index keys. */
    private static void warnAboutDuplicateTitles(List<FormField> fields) {
        Set<String> seen = new HashSet<>();
        Set<String> duplicates = new LinkedHashSet<>();
        for (FormField field : fields) {
            if (!seen.add(field.title())) {
                duplicates.add(field.title());
            }
        }
        if (duplicates.isEmpty()) {
            return;
        }
        System.out.println("ระวัง: ฟอร์มนี้มีคำถามชื่อซ้ำกัน — " + String.join(", ", duplicates));
        System.out.println("       ชื่อซ้ำจะแยกไม่ออก ให้อ้างด้วยเลขข้อแทน เช่น   [0] = คำตอบ");
        System.out.println();
    }

    private static String describe(Map<String, String> person) {
        List<String> parts = new ArrayList<>();
        for (String value : person.values()) {
            if (!value.isBlank()) {
                parts.add(value);
            }
            if (parts.size() == 3) {
                break;
            }
        }
        return String.join(" ", parts);
    }

    /**
     * Appends one line to the results file so progress survives a crash.
     * Synchronized because several threads finish at once when sending in parallel.
     */
    private static synchronized void record(Path file, int row, String status, String note) {
        String line = row + "," + status + "," + LocalDateTime.now().format(TIMESTAMP)
                + ",\"" + note.replace("\"", "'") + "\"" + System.lineSeparator();
        try {
            if (!Files.exists(file)) {
                // Excel only reads a UTF-8 CSV correctly when it starts with a byte order mark
                Files.writeString(file, "﻿แถว,สถานะ,เวลา,หมายเหตุ" + System.lineSeparator(),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE);
            }
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("   เขียนไฟล์ผลลัพธ์ไม่ได้: " + e.getMessage());
        }
    }

    /** Reads which CSV rows are already recorded as sent. */
    private static Set<Integer> readSentRows(Path file) {
        Set<Integer> rows = new HashSet<>();
        if (!Files.exists(file)) {
            return rows;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] cells = line.split(",", 3);
                if (cells.length >= 2 && "ส่งแล้ว".equals(cells[1].trim())) {
                    try {
                        rows.add(Integer.parseInt(cells[0].trim()));
                    } catch (NumberFormatException ignored) {
                        // header or hand-edited line, nothing to skip
                    }
                }
            }
        } catch (IOException e) {
            System.out.println("อ่านไฟล์ผลลัพธ์ไม่ได้ จะถือว่ายังไม่เคยส่งใครเลย: " + e.getMessage());
        }
        return rows;
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
                String trimmed = line.replace("﻿", "").trim();
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

    /** Reads a system property as a number of seconds, falling back if it is missing or not a number. */
    private static long seconds(String property, long fallback) {
        String raw = System.getProperty(property);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            System.out.println("ค่า -D" + property + "=" + raw + " ไม่ใช่ตัวเลข ใช้ค่าเริ่มต้น " + fallback + " แทน");
            return fallback;
        }
    }

    /**
     * Windows consoles start on a code page that cannot render Thai, which turns every message
     * into mojibake. The code page belongs to the console itself, so switching it from a child
     * process switches it for us too — then we make sure we write UTF-8 into it.
     */
    private static void useUtf8Console() {
        if (System.getProperty("os.name", "").toLowerCase().startsWith("windows")) {
            try {
                new ProcessBuilder("cmd", "/c", "chcp", "65001")
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start()
                        .waitFor();
            } catch (IOException e) {
                // leave the console alone; the user can still run chcp 65001 by hand
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
    }

    private static void sleep(long secondsToSleep) {
        try {
            Thread.sleep(Duration.ofSeconds(secondsToSleep).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ถูกสั่งหยุดระหว่างรอรอบถัดไป", e);
        }
    }

    private static void printUsage() {
        System.out.println("""
                ต้องระบุ URL ของฟอร์มก่อน

                  mvn -q compile exec:java "-Dform.url=https://docs.google.com/forms/d/e/XXXX/viewform"

                ตัวเลือกเพิ่มเติม
                  -Dheadless=false   เปิด Chrome ให้เห็นหน้าจอ (ปกติรันแบบซ่อน)
                  -Dsubmit=true      กดส่งฟอร์มจริง (ปกติกรอกอย่างเดียว ไม่ส่ง)
                  -Danswers=<path>   ไฟล์คำตอบ 1 คน (ปกติคือ answers.txt)
                  -Dpeople=<path>    ไฟล์ CSV หลายคน กรอกทีละแถวจนครบ
                  -Dresults=<path>   ไฟล์บันทึกว่าใครส่งแล้ว (ปกติคือ results.csv)
                  -Dmode=http        ส่งตรงไม่เปิด Chrome เร็วกว่ามาก (ปกติใช้ Chrome)
                  -Dthreads=<จำนวน>   ยิงพร้อมกันกี่สาย ใช้ได้กับ -Dmode=http (ปกติ 1)
                  -Dretry=<จำนวน>     ถ้าส่งไม่ผ่าน ลองใหม่กี่ครั้ง (ปกติ 3)
                  -Dstart=HH:mm:ss   นอนรอจนถึงเวลานี้แล้วค่อยยิง (ปกติยิงทันที)
                  -Dschema=<path>    ไฟล์จำโครงฟอร์ม ทำให้ยิงได้ทันทีที่เปิด (ปกติ schema.tsv)
                  -Ddelay=<วินาที>    เว้นระยะระหว่างแต่ละคน (ปกติ 5 วิ)
                  -Dwait=<วินาที>     ถ้าฟอร์มยังปิด ให้รอจนเปิด (ปกติไม่รอ)
                  -Dpoll=<วินาที>     ระหว่างรอ เช็กถี่แค่ไหน (ปกติ 3 วิ)
                """);
    }
}
