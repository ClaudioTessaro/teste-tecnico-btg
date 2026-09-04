package com.claudio.fileprocessor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileProcessorTest {

    @TempDir
    Path folder;

    @Test
    void returnsZeroWhenEveryFileWasRead() throws IOException {
        Path file = Files.writeString(folder.resolve("ok.txt"), "line", StandardCharsets.UTF_8);

        assertThat(FileProcessor.run(List.of(file))).isZero();
    }

    @Test
    void returnsOneWhenAtLeastOneFileFailed() throws IOException {
        Path file = Files.writeString(folder.resolve("ok.txt"), "line", StandardCharsets.UTF_8);
        Path missing = folder.resolve("gone.txt");

        assertThat(FileProcessor.run(List.of(file, missing))).isEqualTo(1);
    }

    @Test
    void takesTheFilesFromTheArguments() {
        assertThat(FileProcessor.filesFrom(new String[] {"a.txt", "b.txt"}))
                .containsExactly(Path.of("a.txt"), Path.of("b.txt"));
    }

    @Test
    void withoutArgumentsItFallsBackToTheSampleFiles() {
        assertThat(FileProcessor.filesFrom(new String[0]))
                .isNotEmpty()
                .allSatisfy(file -> assertThat(file).hasParent(Path.of("sample-data")));
    }
}
