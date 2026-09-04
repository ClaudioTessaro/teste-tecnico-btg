package com.claudio.fileprocessor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ParallelFileReaderTest {

    @TempDir
    Path folder;

    private final Locale defaultLocale = Locale.getDefault();

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(defaultLocale);
    }

    @Test
    void uppercasesEveryLineOfEveryFile() throws IOException {
        Path first = write("first.txt", "alpha", "beta");
        Path second = write("second.txt", "gamma");

        ProcessingResult result = ParallelFileReader.readAll(List.of(first, second));

        assertThat(result.lines()).containsExactly("ALPHA", "BETA", "GAMMA");
        assertThat(result.hasFailures()).isFalse();
    }

    @Test
    @DisplayName("lines come out in the order the files were given, not in the order they finished")
    void keepsTheOrderOfTheArguments() throws IOException {
        Path big = write("big.txt", numberedLines(5_000));
        Path small = write("small.txt", List.of("last"));

        ProcessingResult result = ParallelFileReader.readAll(List.of(big, small));

        assertThat(result.lines()).hasSize(5_001);
        assertThat(result.lines().get(0)).isEqualTo("LINE-0");
        assertThat(result.lines().get(5_000)).isEqualTo("LAST");
    }

    @Test
    void oneUnreadableFileDoesNotDiscardTheRest() throws IOException {
        Path readable = write("readable.txt", "kept");
        Path missing = folder.resolve("missing.txt");

        ProcessingResult result = ParallelFileReader.readAll(List.of(readable, missing));

        assertThat(result.lines()).containsExactly("KEPT");
        assertThat(result.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.file()).isEqualTo(missing);
            assertThat(failure.cause()).isInstanceOf(NoSuchFileException.class);
        });
    }

    @Test
    void readsUtf8WhateverTheDefaultCharsetOfTheMachineIs() throws IOException {
        Path file = folder.resolve("accents.txt");
        Files.write(file, "Café Lumière".getBytes(StandardCharsets.UTF_8));

        assertThat(ParallelFileReader.readAll(List.of(file)).lines()).containsExactly("CAFÉ LUMIÈRE");
    }

    @Test
    @DisplayName("a Turkish default locale does not turn i into a dotted capital I")
    void uppercasingDoesNotDependOnTheDefaultLocale() throws IOException {
        Locale.setDefault(Locale.forLanguageTag("tr"));
        Path file = write("city.txt", "istanbul");

        assertThat(ParallelFileReader.readAll(List.of(file)).lines()).containsExactly("ISTANBUL");
    }

    @Test
    void acceptsAnEmptyBatch() {
        assertThat(ParallelFileReader.readAll(List.of()).lines()).isEmpty();
    }

    @RepeatedTest(5)
    @DisplayName("no line is lost when many files are read at the same time")
    void losesNoLineUnderLoad() throws IOException {
        int fileCount = 64;
        int linesPerFile = 250;

        List<Path> files = new ArrayList<>();
        for (int i = 0; i < fileCount; i++) {
            files.add(write("part-%02d.txt".formatted(i), linesOf(i, linesPerFile)));
        }

        ProcessingResult result = ParallelFileReader.readAll(files);

        List<String> expected = IntStream.range(0, fileCount)
                .boxed()
                .flatMap(i -> linesOf(i, linesPerFile).stream())
                .map(line -> line.toUpperCase(Locale.ROOT))
                .toList();
        assertThat(result.lines()).hasSize(fileCount * linesPerFile);
        assertThat(result.lines()).containsExactlyElementsOf(expected);
    }

    private static List<String> linesOf(int fileIndex, int lineCount) {
        return IntStream.range(0, lineCount)
                .mapToObj(lineIndex -> "file-%02d-line-%03d".formatted(fileIndex, lineIndex))
                .toList();
    }

    private static List<String> numberedLines(int count) {
        return IntStream.range(0, count).mapToObj(i -> "line-" + i).toList();
    }

    private Path write(String name, String... lines) throws IOException {
        return write(name, List.of(lines));
    }

    private Path write(String name, List<String> lines) throws IOException {
        Path file = folder.resolve(name);
        Files.write(file, lines, StandardCharsets.UTF_8);
        return file;
    }
}
