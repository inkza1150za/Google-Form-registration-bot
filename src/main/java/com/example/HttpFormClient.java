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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Talks to the form over plain HTTP — no browser at all.
 *
 * <p>Google Forms ships the whole question list inside {@code data-params} attributes and accepts
 * answers as a form POST to {@code /formResponse}, which is the same thing the page itself does
 * when you press submit. Skipping the browser removes both the startup cost and the per-answer
 * typing, at the price of losing everything the page would have validated for us.
 */
public class HttpFormClient {

    /** %.@.[questionId,"title",description,type,[[entryId,null,required ... */
    private static final Pattern QUESTION = Pattern.compile(
            "\\[(\\d+),\"((?:[^\"\\\\]|\\\\.)*)\",(?:null|\"(?:[^\"\\\\]|\\\\.)*\"),(\\d+),\\[\\[(\\d+),null,(true|false)");

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final String viewUrl;
    private final URI responseUrl;

    public HttpFormClient(String formUrl) {
        this.viewUrl = formUrl;
        this.responseUrl = URI.create(formUrl.replace("/viewform", "/formResponse"));
    }

    /** Downloads the form page and reads its questions out of the markup. */
    public List<FormField> readFields() {
        HttpRequest request = HttpRequest.newBuilder(URI.create(viewUrl))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        // A form that is not accepting responses redirects to .../closedform
        if (response.uri().toString().contains("closedform")) {
            throw new FormClosedException("ฟอร์มยังไม่เปิดรับคำตอบ (ถูกพาไปหน้า closedform)");
        }
        String unescaped = response.body().replace("&quot;", "\"");

        List<FormField> fields = new ArrayList<>();
        Matcher matcher = QUESTION.matcher(unescaped);
        int index = 0;
        while (matcher.find()) {
            fields.add(new FormField(
                    index++,
                    matcher.group(2),
                    typeOf(Integer.parseInt(matcher.group(3))),
                    Boolean.parseBoolean(matcher.group(5)),
                    List.of(),
                    "entry." + matcher.group(4),
                    null));
        }
        return fields;
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
    public void submit(Map<String, String> answersByEntryId) {
        HttpRequest request = HttpRequest.newBuilder(responseUrl)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(encode(answersByEntryId), StandardCharsets.UTF_8))
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
    private static String encode(Map<String, String> answersByEntryId) {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> entry : answersByEntryId.entrySet()) {
            for (String value : entry.getValue().split("\\s*,\\s*")) {
                if (!body.isEmpty()) {
                    body.append('&');
                }
                body.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                        .append('=')
                        .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
            }
        }
        return body.toString();
    }

    private static FormField.Type typeOf(int googleType) {
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
