package com.claudio.fileprocessor;

import java.nio.file.Path;
import java.util.List;


public record ProcessingResult(List<String> lines, List<Failure> failures) {

    public ProcessingResult {
        lines = List.copyOf(lines);
        failures = List.copyOf(failures);
    }

    public boolean hasFailures() {
        return !failures.isEmpty();
    }

    public record Failure(Path file, Throwable cause) {

        public String reason() {
            String message = cause.getMessage();
            String type = cause.getClass().getSimpleName();
            return message == null || message.isBlank() || message.equals(file.toString())
                    ? type
                    : type + ": " + message;
        }
    }
}
