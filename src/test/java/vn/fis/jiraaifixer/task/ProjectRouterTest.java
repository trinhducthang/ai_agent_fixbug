package vn.fis.jiraaifixer.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import vn.fis.jiraaifixer.config.AppProperties.Project;
import vn.fis.jiraaifixer.config.AppProperties.Repo;
import vn.fis.jiraaifixer.task.ProjectRouter.Route;

class ProjectRouterTest {

    private static Project project(String name, List<String> jiraProjects, List<String> keywords) {
        Repo be = new Repo("be", "F:/x/" + name, null, "develop", null, null);
        return new Project(name, name, jiraProjects, keywords, null, List.of(be));
    }

    private final ProjectRouter router = new ProjectRouter();
    private final List<Project> projects = List.of(
            project("qttt", null, List.of("qttt", "quản trị nền tảng số", "pm quản trị")),
            project("ktrs", null, List.of("ktrs", "kiểm tra rà soát", "kiểm tra văn bản", "hệ thống hóa",
                    "rà soát thường xuyên", "pm kiểm tra rà soát")),
            project("tdth", null, List.of("tdth", "theo dõi thi hành", "tổ chức thi hành")),
            project("bpbd", List.of("BTP_GDBD_S7_2026_PM"), List.of("btnn", "gdbđ", "bồi thường nhà nước")));

    private Route route(String key, String module, List<String> components, String summary) {
        return router.route(projects, key, module, components, List.of(), summary);
    }

    @Test
    void jiraProjectKeyWins() {
        Route r = route("BTP_GDBD_S7_2026_PM", null, List.of("Test nội bộ hệ thống BTNN"),
                "BTNN_Quản trị hệ thống _Quản lý cấu hình");
        assertThat(r.project()).isEqualTo("bpbd");
    }

    @Test
    void moduleFieldRoutesKtrs() {
        Route r = route("BTP_NTSPLVN_2026_PM", "2. PM Kiểm tra rà soát", List.of(), "Lỗi hiển thị danh sách");
        assertThat(r.project()).isEqualTo("ktrs");
        assertThat(r.reason()).contains("Module");
    }

    @Test
    void moduleRoutesQtttNts() {
        assertThat(route("BTP_NTSPLVN_2026_PM", "1. PM Quản trị NTS", List.of(), "Lỗi biểu đồ").project())
                .isEqualTo("qttt");
    }

    @Test
    void componentWithPunctuationRoutesKtrs() {
        Route r = route("BTP_NTSPLVN_2026_PM", null,
                List.of("16.5.2. Báo cáo system test Phần mềm hỗ trợ kiểm tra, rà soát VBQPPL (lần 2)"), "x");
        assertThat(r.project()).isEqualTo("ktrs");
    }

    @Test
    void componentRoutesTdth() {
        assertThat(route("BTP_NTSPLVN_2026_PM", "3. PM Tổ chức thi hành", List.of(), "x").project()).isEqualTo("tdth");
    }

    @Test
    void summaryKeywordRoutesQttt() {
        Route r = route("BTP_NTSPLVN_2026_PM", null, List.of(), "Fix phần tích hợp phần mềm qttt với các phần mềm vệ tinh");
        assertThat(r.project()).isEqualTo("qttt");
        assertThat(r.reason()).contains("tiêu đề");
    }

    @Test
    void summaryBeatsCostBucketComponent() {
        // BTP_NTSPLVN_2026_PM-4022: component là mục chi phí của TDTH nhưng tiêu đề nói rõ qttt
        Route r = route("BTP_NTSPLVN_2026_PM", null,
                List.of("8.4. RMSheet phân tích: Phần mềm hỗ trợ theo dõi thi hành VBQPPL"),
                "Fix phần tích hợp phần mềm qttt với các phần mềm vệ tinh");
        assertThat(r.project()).isEqualTo("qttt");
    }

    @Test
    void strongMatchBeatsWeakSummaryMention() {
        assertThat(route("BTP_NTSPLVN_2026_PM", "2. PM Kiểm tra rà soát", List.of(), "Đồng bộ danh mục từ qttt sang")
                .project()).isEqualTo("ktrs");
    }

    @Test
    void tieOrNoMatchIsUndetermined() {
        assertThat(route("BTP_NTSPLVN_2026_PM", null, List.of(), "Họp với khách hàng").project()).isNull();
        assertThat(route("BTP_NTSPLVN_2026_PM", null, List.of(), "Đồng bộ qttt và tdth").project()).isNull();
    }

    @Test
    void keywordMatchesWholeWordsOnly() {
        assertThat(route("BTP_NTSPLVN_2026_PM", null, List.of(), "abcqtttxyz").project()).isNull();
    }

    @Test
    void singleSoftwareProfileTakesEverything() {
        Route r = router.route(List.of(project("shop", null, List.of("shop"))), "ABC", null, List.of(), List.of(), "Lỗi giỏ hàng");
        assertThat(r.project()).isEqualTo("shop");
    }

    @Test
    void emptyProfileIsUndetermined() {
        assertThat(router.route(List.of(), "ABC", null, List.of(), List.of(), "x").project()).isNull();
    }
}
