package vn.fis.jiraaifixer.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class AttachmentReadCheckTest {

    private final Path dir = Path.of("F:/ws/K-1/_jira");
    private final Path a = dir.resolve("01-loi.png");
    private final Path b = dir.resolve("02-Man_hinh.JPG");
    private final Path c = dir.resolve("03-log.txt");

    @Test
    void matchesRelativeAbsoluteAndMixedSeparators() {
        List<Path> missing = FixTaskService.unread(List.of(a, b, c),
                List.of("_jira\\01-loi.png", "F:/ws/K-1/_jira/02-man_hinh.jpg"));

        assertThat(missing).containsExactly(c);
    }

    @Test
    void nothingReadMeansAllMissing() {
        assertThat(FixTaskService.unread(List.of(a, b), List.of("be/src/Main.java"))).containsExactly(a, b);
    }

    @Test
    void noAttachmentsNothingMissing() {
        assertThat(FixTaskService.unread(List.of(), List.of("x"))).isEmpty();
    }
}
