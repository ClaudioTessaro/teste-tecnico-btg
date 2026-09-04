package com.claudio.fileprocessor;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;


public final class FileProcessor {

    private static final Path SAMPLE_DATA = Path.of("sample-data");

    private FileProcessor() {
    }

    public static void main(String[] args) {
        List<Path> files = filesFrom(args);
        if (files.isEmpty()) {
            System.err.println("usage: file-processor <file> [<file> ...]");
            System.exit(2);
        }
        System.exit(run(files));
    }


    static List<Path> filesFrom(String[] args) {
        if (args.length > 0) {
            return Arrays.stream(args).map(Path::of).toList();
        }
        if (!Files.isDirectory(SAMPLE_DATA)) {
            return List.of();
        }
        System.err.println("no files given, reading " + SAMPLE_DATA + "/");
        try (Stream<Path> entries = Files.list(SAMPLE_DATA)) {
            return entries.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            System.err.println("could not list " + SAMPLE_DATA + ": " + e);
            return List.of();
        }
    }

    static int run(List<Path> files) {
        ProcessingResult result = ParallelFileReader.readAll(files);

        PrintWriter out = new PrintWriter(System.out, false, StandardCharsets.UTF_8);
        result.lines().forEach(out::println);
        out.flush();

        result.failures().forEach(failure ->
                System.err.println("failed: " + failure.file() + " (" + failure.reason() + ")"));
        System.err.printf("read %d of %d file(s), %d line(s)%n",
                files.size() - result.failures().size(), files.size(), result.lines().size());

        return result.hasFailures() ? 1 : 0;
    }
}
