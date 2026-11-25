package vn.fis.jiraaifixer.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import vn.fis.jiraaifixer.config.AppProperties;
import vn.fis.jiraaifixer.config.AppProperties.Project;
import vn.fis.jiraaifixer.config.AppProperties.Repo;
import vn.fis.jiraaifixer.task.ProjectRouter.Route;

class ProjectRouterTest {

    private static Project project(String name, List<String> jiraProjects, List<String> keywords) {
        Repo be = new Repo("be", "F:/x/" + name, null, "develop", null, null);
        return new Project(name, name, jiraProjects, keywords, null, List.of(be));
    }

    private final ProjectRouter router = new ProjectRouter(new AppProperties(null, null, null, List.of(
            project("qttt", null, List.of("qttt", "quản trị nền tảng số", "pm quản trị")),
            project("ktrs", null, List.of("ktrs", "kiểm tra rà soát", "kiểm tra văn bản", "hệ thống hóa",
                    "rà soát thường xuyên", "pm kiểm tra rà soát")),
            project("tdth", null, List.of("tdth", "theo dõi thi hành", "tổ chức thi hành")),
            project("bpbd", List.of("BTP_GDBD_S7_2026_PM"), List.of("btnn", "gdbđ", "bồi thường nhà nước"))))
    );

    @Test
    void jiraProjectKeyWins() {
        Route r = router.route("BTP_GDBD_S7_2026_PM", null, List.of("Test nội bộ hệ thống BTNN"), List.of(),
                "BTNN_Quản trị hệ thống _Quản lý cấu hình");
        assertThat(r.project()).isEqualTo("bpbd");
    }

    @Test
    void moduleFieldRoutesKtrs() {
        Route r = router.route("BTP_NTSPLVN_2026_PM", "2. PM Kiểm tra rà soát", List.of(), List.of(),
                "Lỗi hiển thị danh sách");
        assertThat(r.project()).isEqualTo("ktrs");
        assertThat(r.reason()).contains("Module");
    }

    @Test
    void moduleRoutesQtttNts() {
        Route r = router.route("BTP_NTSPLVN_2026_PM", "1. PM Quản trị NTS", List.of(), List.of(), "Lỗi biểu đồ");
        assertThat(r.project()).isEqualTo("qttt");
    }

    @Test
    void componentWithPunctuationRoutesKtrs() {
        Route r = router.route("BTP_NTSPLVN_2026_PM", null,
                List.of("16.5.2. Báo cáo system test Phần mềm hỗ trợ kiểm tra, rà soát VBQPPL (lần 2)"), List.of(), "x");
        assertThat(r.project()).isEqualTo("ktrs");
    }

    @Test
    void componentRoutesTdth() {
        Route r = router.route("BTP_NTSPLVN_2026_PM", "3. PM Tổ chức thi hành", List.of(), List.of(), "x");
        assertThat(r.project()).isEqualTo("tdth");
    }

    @Test
    void summaryKeywordRoutesQttt() {
        Route r = router.route("BTP_NTSPLVN_2026_PM", null, List.of(), List.of(),
                "Fix phần tích hợp phần mềm qttt với các phần mềm vệ tinh");
        assertThat(r.project()).isEqualTo("qttt");
        assertThat(r.reason()).contains("tiêu đề");
    }

    @Test
    void strongMatchBeatsWeakSummaryMention() {
        Route r = router.route("BTP_NTSPLVN_2026_PM", "2. PM Kiểm tra rà soát", List.of(), List.of(),
                "Đồng bộ danh mục từ qttt sang");
        assertThat(r.project()).isEqualTo("ktrs");
    }

    @Test
    void summaryBeatsCostBucketComponent() {
        // BTP_NTSPLVN_2026_PM-4022: component là mục chi phí của TDTH nhưng tiêu đề nói rõ qttt
        Route r = router.route("BTP_NTSPLVN_2026_PM", null,
                List.of("8.4. RMSheet phân tích: Phần mềm hỗ trợ theo dõi thi hành VBQPPL"), List.of(),
                "Fix phần tích hợp phần mềm qttt với các phần mềm vệ tinh");
        assertThat(r.project()).isEqualTo("qttt");
    }

    @Test
    void tieOrNoMatchIsUndetermined() {
        assertThat(router.route("BTP_NTSPLVN_2026_PM", null, List.of(), List.of(), "Họp với khách hàng").project())
                .isNull();
        assertThat(router.route("BTP_NTSPLVN_2026_PM", null, List.of(), List.of(), "Đồng bộ qttt và tdth").project())
                .isNull();
    }

    @Test
    void keywordMatchesWholeWordsOnly() {
        assertThat(router.route("BTP_NTSPLVN_2026_PM", null, List.of(), List.of(), "abcqtttxyz").project()).isNull();
    }
}
