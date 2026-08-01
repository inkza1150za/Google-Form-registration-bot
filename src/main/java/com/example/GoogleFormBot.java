package com.example;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

/**
 * Drives a Google Form: reads its questions, fills them in, and submits.
 */
public class GoogleFormBot implements AutoCloseable {

    private static final By QUESTION = By.cssSelector("div[role='listitem']");
    private static final By HEADING = By.cssSelector("div[role='heading']");
    private static final By RADIO = By.cssSelector("div[role='radio']");
    private static final By CHECKBOX = By.cssSelector("div[role='checkbox']");
    private static final By LISTBOX = By.cssSelector("div[role='listbox']");
    private static final By SHORT_INPUT = By.cssSelector("input[type='text']");
    private static final By TEXTAREA = By.cssSelector("textarea");

    private final WebDriver driver;
    private final WebDriverWait wait;

    public GoogleFormBot(boolean headless) {
        ChromeOptions options = new ChromeOptions();
        if (headless) {
            options.addArguments("--headless=new");
        }
        options.addArguments("--window-size=1280,1400");
        this.driver = new ChromeDriver(options);
        this.wait = new WebDriverWait(driver, Duration.ofSeconds(20));
    }

    /** Opens the form and waits for its questions to render. Returns the page title. */
    public String open(String url) {
        driver.get(url);
        try {
            wait.until(ExpectedConditions.presenceOfElementLocated(QUESTION));
        } catch (TimeoutException e) {
            throw new IllegalStateException(
                    "ไม่พบคำถามในหน้านี้ — ต้องเป็นลิงก์ฟอร์มที่ลงท้ายด้วย /viewform และเปิดได้โดยไม่ต้องล็อกอิน"
                            + " (หน้าที่เปิดได้คือ \"" + driver.getTitle() + "\")", e);
        }
        return driver.getTitle();
    }

    public List<FormField> readFields() {
        List<WebElement> containers = driver.findElements(QUESTION);
        List<FormField> fields = new ArrayList<>();
        int index = 0;
        for (WebElement container : containers) {
            List<WebElement> headings = container.findElements(HEADING);
            if (headings.isEmpty()) {
                continue; // section header or image block, not a question
            }
            String rawTitle = headings.get(0).getText().trim();
            boolean required = rawTitle.endsWith("*");
            String title = required ? rawTitle.substring(0, rawTitle.length() - 1).trim() : rawTitle;

            FormField.Type type = detectType(container);
            fields.add(new FormField(index++, title, type, required, readOptions(container, type), container));
        }
        return fields;
    }

    private FormField.Type detectType(WebElement container) {
        if (!container.findElements(TEXTAREA).isEmpty()) {
            return FormField.Type.PARAGRAPH;
        }
        if (!container.findElements(By.cssSelector("input[type='date']")).isEmpty()) {
            return FormField.Type.DATE;
        }
        if (!container.findElements(By.cssSelector("input[type='time']")).isEmpty()) {
            return FormField.Type.TIME;
        }
        if (!container.findElements(RADIO).isEmpty()) {
            return FormField.Type.RADIO;
        }
        if (!container.findElements(CHECKBOX).isEmpty()) {
            return FormField.Type.CHECKBOX;
        }
        if (!container.findElements(LISTBOX).isEmpty()) {
            return FormField.Type.DROPDOWN;
        }
        if (!container.findElements(SHORT_INPUT).isEmpty()) {
            return FormField.Type.SHORT_TEXT;
        }
        return FormField.Type.UNKNOWN;
    }

    private List<String> readOptions(WebElement container, FormField.Type type) {
        By locator = switch (type) {
            case RADIO -> RADIO;
            case CHECKBOX -> CHECKBOX;
            case DROPDOWN -> By.cssSelector("div[role='option'][data-value]");
            default -> null;
        };
        if (locator == null) {
            return List.of();
        }
        List<String> options = new ArrayList<>();
        for (WebElement option : container.findElements(locator)) {
            String label = type == FormField.Type.DROPDOWN
                    ? option.getDomAttribute("data-value")
                    : option.getDomAttribute("aria-label");
            if (label != null && !label.isBlank()) {
                options.add(label);
            }
        }
        return options;
    }

    /** Fills one question. Throws IllegalArgumentException if the answer does not match any option. */
    public void fill(FormField field, String answer) {
        WebElement container = field.container();
        switch (field.type()) {
            case SHORT_TEXT, DATE, TIME -> type(
                    container.findElement(By.cssSelector("input:not([type='hidden'])")), answer);
            case PARAGRAPH -> type(container.findElement(TEXTAREA), answer);
            case RADIO -> click(pickOption(container, RADIO, answer, field));
            case CHECKBOX -> {
                for (String choice : answer.split("\\s*,\\s*")) {
                    click(pickOption(container, CHECKBOX, choice, field));
                }
            }
            case DROPDOWN -> {
                click(container.findElement(LISTBOX));
                click(pickDropdownOption(container, answer, field));
            }
            case UNKNOWN -> throw new IllegalArgumentException(
                    "ไม่รู้จักชนิดของคำถาม \"" + field.title() + "\" — ต้องกรอกเอง");
        }
    }

    private WebElement pickOption(WebElement container, By locator, String answer, FormField field) {
        for (WebElement option : container.findElements(locator)) {
            if (answer.equals(option.getDomAttribute("aria-label"))) {
                return option;
            }
        }
        throw new IllegalArgumentException(
                "คำถาม \"" + field.title() + "\" ไม่มีตัวเลือก \"" + answer + "\" (มี: "
                        + String.join(" | ", field.options()) + ")");
    }

    private WebElement pickDropdownOption(WebElement container, String answer, FormField field) {
        for (WebElement option : container.findElements(By.cssSelector("div[role='option'][data-value]"))) {
            if (answer.equals(option.getDomAttribute("data-value"))) {
                return option;
            }
        }
        throw new IllegalArgumentException(
                "คำถาม \"" + field.title() + "\" ไม่มีตัวเลือก \"" + answer + "\" (มี: "
                        + String.join(" | ", field.options()) + ")");
    }

    /** Clicks the submit button and waits for the confirmation page. */
    public void submit() {
        WebElement button = driver.findElement(By.xpath(
                "//div[@role='button'][.//span[normalize-space()='Submit' or normalize-space()='ส่ง']]"));
        click(button);
        wait.until(ExpectedConditions.or(
                ExpectedConditions.urlContains("formResponse"),
                ExpectedConditions.presenceOfElementLocated(By.cssSelector("a[href*='viewform']"))));
    }

    private void type(WebElement element, String text) {
        scrollTo(element);
        element.clear();
        element.sendKeys(text);
    }

    private void click(WebElement element) {
        scrollTo(element);
        element.click();
    }

    private void scrollTo(WebElement element) {
        ((JavascriptExecutor) driver)
                .executeScript("arguments[0].scrollIntoView({block:'center'});", element);
    }

    @Override
    public void close() {
        driver.quit();
    }
}
