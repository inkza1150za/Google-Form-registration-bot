package com.example;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Talks to the form over plain HTTP — no browser at all.
 *
 * <p>The page only ever renders one section at a time, but it carries the definition of the
 * <em>whole</em> form in a script variable called {@code FB_PUBLIC_LOAD_DATA_}. Reading that gives
 * every question on every page in one download, so a form split across several "next" pages can
 * still be answered with a single POST.
 */
public class HttpFormClient {

    private static final Pattern LOAD_DATA = Pattern.compile("FB_PUBLIC_LOAD_DATA_ = (\\[[\\s\\S]*?\\]);");
    private static final Pattern FBZX = Pattern.compile("name=\"fbzx\"\\s+value=\"([^\"]+)\"");

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final String viewUrl;
    private final URI responseUrl;
    private String fbzx;

    public HttpFormClient(String formUrl) {
        this.viewUrl = formUrl;
        this.responseUrl = URI.create(formUrl.replace("/viewform", "/formResponse"));
    }

    /** The token the page hides in its form, or null if we have not loaded the page. */
    public String token() {
        return fbzx;
    }

    public void useToken(String token) {
        this.fbzx = token;
    }

    /** Downloads the form page and reads every question out of its embedded definition. */
    public List<FormField> readFields() {
        HttpRequest request = HttpRequest.newBuilder(URI.create(viewUrl))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.uri().toString().contains("closedform")) {
            throw new FormClosedException("ฟอร์มยังไม่เปิดรับคำตอบ (ถูกพาไปหน้า closedform)");
        }

        String html = response.body();
        Matcher token = FBZX.matcher(html);
        if (token.find()) {
            fbzx = token.group(1);
        }

        Matcher loadData = LOAD_DATA.matcher(html);
        if (!loadData.find()) {
            return List.of();
        }
        return parse(Json.parse(loadData.group(1)));
    }

    /** Re-downloads the form until it starts accepting responses, then returns its questions. */
    public List<FormField> waitUntilOpen(Duration pollEvery, Duration giveUpAfter) {
        long deadline = System.nanoTime() + giveUpAfter.toNanos();
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return readFields();
            } catch (FormClosedException e) {
                if (System.nanoTime() > deadline) {
                    throw new FormClosedException(
                            "รอจนหมดเวลาแล้วฟอร์มยังไม่เปิด (ลองไป " + attempt + " ครั้ง)");
                }
                System.out.println("  ครั้งที่ " + attempt + ": ยังปิดอยู่ รออีก " + pollEvery.toSeconds() + " วิ");
                try {
                    Thread.sleep(pollEvery.toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("ถูกสั่งหยุดระหว่างรอฟอร์มเปิด", interrupted);
                }
            }
        }
    }

    /**
     * Posts one response and checks that it was actually recorded.
     *
     * <p>HTTP 200 on its own proves nothing: when Google refuses a response it answers 200 and
     * simply hands the form back. A recorded response gets the confirmation page instead, which
     * carries no {@code data-params} because it has no questions on it.
     */
    public void submit(Map<String, String> answersByEntryId, String pageHistory) {
        HttpRequest request = HttpRequest.newBuilder(responseUrl)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(
                        encode(answersByEntryId, pageHistory), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() != 200) {
            throw new IllegalStateException("ฟอร์มตอบกลับ HTTP " + response.statusCode()
                    + " — คำตอบอาจไม่ครบหรือไม่ตรงกับตัวเลือกที่ฟอร์มยอมรับ");
        }
        if (response.uri().toString().contains("closedform")) {
            throw new FormClosedException("ฟอร์มปิดรับคำตอบไปแล้ว คำตอบนี้ไม่ถูกบันทึก");
        }
        if (response.body().contains("data-params")) {
            throw new IllegalStateException(
                    "ฟอร์มส่งหน้าเดิมกลับมาแทนหน้ายืนยัน — คำตอบนี้ไม่ถูกบันทึก");
        }
    }

    /**
     * Walks the sections the way a person clicking "next" would, and returns the trail Google
     * expects in {@code pageHistory}. A choice can jump straight to a later section, so the trail
     * is not simply every page in order.
     */
    public static String pageHistory(List<FormField> fields, Map<String, String> answersByEntryId) {
        int lastPage = 0;
        for (FormField field : fields) {
            lastPage = Math.max(lastPage, field.page());
        }

        List<String> trail = new ArrayList<>();
        int page = 0;
        while (page >= 0 && page <= lastPage) {
            trail.add(String.valueOf(page));
            int next = FormField.CONTINUE;
            for (FormField field : fields) {
                if (field.page() != page) {
                    continue;
                }
                String answer = answersByEntryId.get(field.entryId());
                if (answer != null) {
                    int target = field.targetOf(answer);
                    if (target != FormField.CONTINUE) {
                        next = target;
                    }
                }
            }
            if (next == FormField.SUBMIT) {
                break;
            }
            page = next == FormField.CONTINUE ? page + 1 : next;
        }
        return String.join(",", trail);
    }

    /** Turns the parsed {@code FB_PUBLIC_LOAD_DATA_} into questions, sections and all. */
    private static List<FormField> parse(Object root) {
        List<Object> items = findItems(root);
        if (items.isEmpty()) {
            return List.of();
        }

        // Sections have to be numbered first: a choice on page 0 can point at the last page
        Map<Integer, Integer> pageOfSection = new HashMap<>();
        int pages = 0;
        for (Object item : items) {
            if (isPageBreak(item)) {
                pages++;
                Integer id = Json.number(Json.at(item, 0));
                if (id != null) {
                    pageOfSection.put(id, pages);
                }
            }
        }

        List<FormField> fields = new ArrayList<>();
        int page = 0;
        int index = 0;
        for (Object item : items) {
            if (isPageBreak(item)) {
                page++;
                continue;
            }
            Object block = Json.at(Json.at(item, 4), 0);
            Integer entryId = Json.number(Json.at(block, 0));
            if (entryId == null) {
                continue; // a heading or a block of text, nothing to answer
            }

            List<String> options = new ArrayList<>();
            List<Integer> targets = new ArrayList<>();
            for (Object choice : Json.list(Json.at(block, 1))) {
                String label = Json.text(Json.at(choice, 0));
                if (label == null) {
                    continue;
                }
                options.add(label);
                targets.add(pageFor(Json.number(Json.at(choice, 2)), pageOfSection));
            }

            fields.add(new FormField(
                    index++,
                    Json.text(Json.at(item, 1)),
                    typeOf(Json.number(Json.at(item, 3))),
                    Integer.valueOf(1).equals(Json.number(Json.at(block, 2))),
                    List.copyOf(options),
                    List.copyOf(targets),
                    page,
                    "entry." + entryId,
                    null));
        }
        return fields;
    }

    /** Google marks "go to next section" as -1 and "submit the form" as -2 or -3. */
    private static int pageFor(Integer goToSection, Map<Integer, Integer> pageOfSection) {
        if (goToSection == null || goToSection == -1) {
            return FormField.CONTINUE;
        }
        if (goToSection < 0) {
            return FormField.SUBMIT;
        }
        return pageOfSection.getOrDefault(goToSection, FormField.CONTINUE);
    }

    private static boolean isPageBreak(Object item) {
        return Integer.valueOf(8).equals(Json.number(Json.at(item, 3)));
    }

    /**
     * Finds the question list inside the nested load data. Its shape is what identifies it: a list
     * whose entries start with a number and a title and carry a type code.
     */
    private static List<Object> findItems(Object node) {
        List<Object> list = Json.list(node);
        if (list.isEmpty()) {
            return List.of();
        }
        boolean looksLikeItems = true;
        for (Object entry : list) {
            List<Object> item = Json.list(entry);
            if (item.size() < 4 || Json.number(Json.at(item, 0)) == null
                    || Json.text(Json.at(item, 1)) == null || Json.number(Json.at(item, 3)) == null) {
                looksLikeItems = false;
                break;
            }
        }
        if (looksLikeItems) {
            return list;
        }
        for (Object child : list) {
            List<Object> found = findItems(child);
            if (!found.isEmpty()) {
                return found;
            }
        }
        return List.of();
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        try {
            return client.send(request, handler);
        } catch (IOException e) {
            throw new IllegalStateException("ติดต่อฟอร์มไม่ได้: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ถูกสั่งหยุดระหว่างติดต่อฟอร์ม", e);
        }
    }

    /** Checkbox answers arrive comma separated and go out as one parameter per choice. */
    private String encode(Map<String, String> answersByEntryId, String pageHistory) {
        Map<String, String> extras = new LinkedHashMap<>();
        extras.put("fvv", "1");
        if (pageHistory != null && !pageHistory.isBlank()) {
            extras.put("pageHistory", pageHistory);
        }
        if (fbzx != null) {
            extras.put("fbzx", fbzx);
        }
        extras.put("submissionTimestamp", "-1");

        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> entry : answersByEntryId.entrySet()) {
            for (String value : entry.getValue().split("\\s*,\\s*")) {
                append(body, entry.getKey(), value);
            }
        }
        extras.forEach((name, value) -> append(body, name, value));
        return body.toString();
    }

    private static void append(StringBuilder body, String name, String value) {
        if (!body.isEmpty()) {
            body.append('&');
        }
        body.append(URLEncoder.encode(name, StandardCharsets.UTF_8))
                .append('=')
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    private static FormField.Type typeOf(Integer googleType) {
        if (googleType == null) {
            return FormField.Type.UNKNOWN;
        }
        return switch (googleType) {
            case 0 -> FormField.Type.SHORT_TEXT;
            case 1 -> FormField.Type.PARAGRAPH;
            case 2, 5 -> FormField.Type.RADIO;
            case 3 -> FormField.Type.DROPDOWN;
            case 4 -> FormField.Type.CHECKBOX;
            case 9 -> FormField.Type.DATE;
            case 10 -> FormField.Type.TIME;
            default -> FormField.Type.UNKNOWN;
        };
    }
}
