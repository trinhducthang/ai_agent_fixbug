package vn.fis.jiraaifixer.profile;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GitUrlsTest {

    @Test
    void webLinksBecomeCloneUrls() {
        assertThat(GitUrls.toCloneUrl("https://git.fis.vn/fis-gs/gs-hn/btp_ntsplvn_2026_pm/ktrs/btp-ntsplvn-ktrs-be/-/tree/dev"))
                .isEqualTo("https://git.fis.vn/fis-gs/gs-hn/btp_ntsplvn_2026_pm/ktrs/btp-ntsplvn-ktrs-be.git");
        assertThat(GitUrls.toCloneUrl("https://github.com/a/b/tree/main/src")).isEqualTo("https://github.com/a/b.git");
        assertThat(GitUrls.toCloneUrl("https://user:secret@git.fis.vn/g/r.git")).isEqualTo("https://git.fis.vn/g/r.git");
        assertThat(GitUrls.toCloneUrl("git@git.fis.vn:g/r")).isEqualTo("git@git.fis.vn:g/r.git");
        assertThat(GitUrls.toCloneUrl("https://git.fis.vn/onlyone")).isNull();
        assertThat(GitUrls.toCloneUrl("F:/qttt")).isNull();
    }

    @Test
    void normalizeMatchesAcrossForms() {
        String a = GitUrls.normalize("https://ThangTD46@git.fis.vn/G/Repo.git");
        assertThat(a).isEqualTo("git.fis.vn/g/repo");
        assertThat(GitUrls.normalize("git@git.fis.vn:g/repo.git")).isEqualTo(a);
        assertThat(GitUrls.normalize("https://git.fis.vn/g/repo/")).isEqualTo(a);
    }

    @Test
    void segmentsAndNames() {
        String u = "https://git.fis.vn/fis-gs/gs-hn/btp_gdbd_s7_2026_pm/btp_bpbd_be.git";
        assertThat(GitUrls.repoName(u)).isEqualTo("btp_bpbd_be");
        assertThat(GitUrls.parentGroup(u)).isEqualTo("btp_gdbd_s7_2026_pm");
        assertThat(GitUrls.pathSegments(u)).contains("btp_gdbd_s7_2026_pm");
        assertThat(GitUrls.stripCredentials("https://u:p@h/x.git")).isEqualTo("https://h/x.git");
    }
}
