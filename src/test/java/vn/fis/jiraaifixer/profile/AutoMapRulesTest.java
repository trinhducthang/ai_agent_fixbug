package vn.fis.jiraaifixer.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import vn.fis.jiraaifixer.jira.JiraClient.FieldInfo;

class AutoMapRulesTest {

    @TempDir
    Path dir;

    @Test
    void targetBranchPriority() {
        assertThat(SourceAnalyzer.pickTarget(List.of("main", "dev", "feat/x"))).isEqualTo("dev");
        assertThat(SourceAnalyzer.pickTarget(List.of("main", "develop", "dev"))).isEqualTo("develop");
        assertThat(SourceAnalyzer.pickTarget(List.of("main"))).isEqualTo("main");
        assertThat(SourceAnalyzer.pickTarget(List.of("release"))).isNull();
    }

    @Test
    void roleByRepoName() {
        assertThat(SourceAnalyzer.guessRoleByName("btp-ntsplvn-ktrs-be")).isEqualTo("be");
        assertThat(SourceAnalyzer.guessRoleByName("btp_bpbd_fe")).isEqualTo("fe");
        assertThat(SourceAnalyzer.guessRoleByName("shop-backend")).isEqualTo("be");
        assertThat(SourceAnalyzer.guessRoleByName("btp_bpbd_ui_docs")).isEqualTo("unknown");
    }

    @Test
    void softwareNameAndKeywords() {
        assertThat(SourceAnalyzer.sanitize("Quản Trị_Mới")).isEqualTo("quan_tri_moi");
        assertThat(SourceAnalyzer.sanitize("Đồng bộ  dữ liệu")).isEqualTo("dong_bo_du_lieu");
        assertThat(SourceAnalyzer.sanitize("bpbd_s7")).isEqualTo("bpbd_s7");
        assertThat(SourceAnalyzer.keywords("bpbd_s7")).containsExactly("bpbd_s7", "bpbd");
        assertThat(SourceAnalyzer.keywords("ktrs")).containsExactly("ktrs");
    }

    @Test
    void sharedNodeModulesForMonorepo() throws Exception {
        Files.writeString(dir.resolve("package.json"), "{}");
        Files.createDirectories(dir.resolve("node_modules"));
        Files.createDirectories(dir.resolve("apps/admin/node_modules"));
        Files.writeString(dir.resolve("apps/admin/package.json"), "{}");
        Files.createDirectories(dir.resolve("apps/empty"));
        Files.createDirectories(dir.resolve("packages/ui/node_modules")); // không có package.json -> bỏ

        assertThat(SourceAnalyzer.sharedDirs(dir)).containsExactly("node_modules", "apps/admin/node_modules");
    }

    @Test
    void findsNestedGitReposButNotInsideThem() throws Exception {
        Files.createDirectories(dir.resolve("be/.git"));
        Files.createDirectories(dir.resolve("be/sub/.git"));
        Files.createDirectories(dir.resolve("group/fe/.git"));
        Files.createDirectories(dir.resolve(".hidden/x/.git"));

        assertThat(SourceAnalyzer.findGitRepos(dir, 2)).containsExactly(dir.resolve("be"), dir.resolve("group/fe"));
    }

    @Test
    void suggestedJqlUsesOnlyExistingStatuses() {
        assertThat(ProfileController.suggestJql(List.of("ABC"), List.of("Open", "In Progress", "Done")))
                .isEqualTo("project in (ABC) AND status in (\"Open\", \"In Progress\") AND assignee = currentUser() ORDER BY priority DESC, updated DESC");
        assertThat(ProfileController.suggestJql(List.of(), List.of("To Do", "Done")))
                .startsWith("resolution = Unresolved AND assignee = currentUser()");
    }

    @Test
    void moduleFieldDetection() {
        List<FieldInfo> fields = List.of(new FieldInfo("summary", "Summary"),
                new FieldInfo("customfield_10100", "Sub Module"), new FieldInfo("customfield_10700", "Module"));
        assertThat(ProfileController.findModuleField(fields).id()).isEqualTo("customfield_10700");
        assertThat(ProfileController.findModuleField(List.of(new FieldInfo("customfield_1", "Sub Module"))).id())
                .isEqualTo("customfield_1");
        assertThat(ProfileController.findModuleField(List.of(new FieldInfo("summary", "Summary")))).isNull();
    }
}
