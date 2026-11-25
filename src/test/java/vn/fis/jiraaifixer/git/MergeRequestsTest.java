package vn.fis.jiraaifixer.git;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MergeRequestsTest {

    @Test
    void findsMrUrlInGitLabPushOutput() {
        String out = """
                remote:
                remote: View merge request for bugfix/ABC-1:
                remote:   https://git.fis.vn/fis-gs/gs-hn/proj/repo/-/merge_requests/42
                remote:
                To https://git.fis.vn/fis-gs/gs-hn/proj/repo.git
                """;
        assertThat(MergeRequests.findMrUrl(out))
                .contains("https://git.fis.vn/fis-gs/gs-hn/proj/repo/-/merge_requests/42");
        assertThat(MergeRequests.findMrUrl("Everything up-to-date")).isEmpty();
    }

    @Test
    void webUrlFromRemoteVariants() {
        assertThat(MergeRequests.webUrl("https://user:tok@git.fis.vn/a/b/repo.git")).isEqualTo("https://git.fis.vn/a/b/repo");
        assertThat(MergeRequests.webUrl("git@git.fis.vn:a/b/repo.git")).isEqualTo("https://git.fis.vn/a/b/repo");
        assertThat(MergeRequests.webUrl("ssh://git@git.fis.vn/a/repo.git")).isEqualTo("https://git.fis.vn/a/repo");
    }

    @Test
    void newMrUrlEncodesBranches() {
        assertThat(MergeRequests.newMrUrl("https://git.fis.vn/a/repo.git", "bugfix/ABC-1", "develop"))
                .isEqualTo("https://git.fis.vn/a/repo/-/merge_requests/new?merge_request%5Bsource_branch%5D=bugfix%2FABC-1"
                        + "&merge_request%5Btarget_branch%5D=develop");
    }
}
