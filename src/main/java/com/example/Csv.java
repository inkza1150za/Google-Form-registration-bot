package com.example;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal CSV reader. The first non-blank line is the header; values may be wrapped in
 * double quotes so they can contain commas (addresses usually do).
 */
final class Csv {

    private Csv() {
    }

    /** Returns one map per row, keyed by header column. */
    static List<Map<String, String>> read(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("อ่านไฟล์ไม่ได้: " + file, e);
        }

        if (!lines.isEmpty()) {
            // Excel's "CSV UTF-8" starts the file with a byte order mark, which would otherwise
            // become part of the first column name and stop it matching any question
            lines.set(0, lines.get(0).replace("﻿", ""));
        }

        List<String> header = null;
        List<Map<String, String>> rows = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            List<String> cells = splitLine(line);
            if (header == null) {
                header = cells;
                continue;
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < header.size(); i++) {
                row.put(header.get(i), i < cells.size() ? cells.get(i) : "");
            }
            rows.add(row);
        }
        return rows;
    }

    private static List<String> splitLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c != '"') {
                    cell.append(c);
                } else if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"'); // "" inside quotes means one literal quote
                    i++;
                } else {
                    inQuotes = false;
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                cells.add(cell.toString().trim());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        cells.add(cell.toString().trim());
        return cells;
    }
}
