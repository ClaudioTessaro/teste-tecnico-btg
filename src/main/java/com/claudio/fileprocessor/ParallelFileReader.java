package com.claudio.fileprocessor;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;


public final class ParallelFileReader {

    private static final Charset CHARSET = StandardCharsets.UTF_8;
    private static final Locale LOCALE = Locale.ROOT;

    private ParallelFileReader() {
    }

    public static ProcessingResult readAll(List<Path> files) {
        List<String> lines = new ArrayList<>();
        List<ProcessingResult.Failure> failures = new ArrayList<>();


        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<String>>> pending = files.stream()
                    .map(file -> executor.submit(() -> read(file)))
                    .toList();

            for (int i = 0; i < files.size(); i++) {
                try {
                    lines.addAll(pending.get(i).get());
                } catch (ExecutionException e) {
                    failures.add(new ProcessingResult.Failure(files.get(i), e.getCause()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    pending.forEach(task -> task.cancel(true));
                    throw new IllegalStateException("interrupted while reading " + files.get(i), e);
                }
            }
        }

        return new ProcessingResult(lines, failures);
    }

    static List<String> read(Path file) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, CHARSET)) {
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line.toUpperCase(LOCALE));
            }
            return lines;
        }
    }
}
