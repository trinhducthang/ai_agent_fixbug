package vn.fis.jiraaifixer.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import vn.fis.jiraaifixer.profile.JiraLinkParser.JiraLink;

class JiraLinkParserTest {

    @Test
    void issueLink() {
        JiraLink l = JiraLinkParser.parse("https://jira.fis.com.vn/browse/BTP_NTSPLVN_2026_PM-4022");
        assertThat(l.baseUrl()).isEqualTo("https://jira.fis.com.vn");
        assertThat(l.projectKeys()).containsExactly("BTP_NTSPLVN_2026_PM");
        assertThat(l.issueKey()).isEqualTo("BTP_NTSPLVN_2026_PM-4022");
    }

    @Test
    void searchLinkWithJqlAndContextPath() {
        JiraLink l = JiraLinkParser.parse("https://host.example:8443/jira/issues/?jql=project%20in%20(ABC%2C%20%22XYZ_1%22)%20AND%20status%20%3D%20Open");
        assertThat(l.baseUrl()).isEqualTo("https://host.example:8443/jira");
        assertThat(l.projectKeys()).containsExactly("ABC", "XYZ_1");
    }

    @Test
    void jqlProjectEquals() {
        JiraLink l = JiraLinkParser.parse("https://jira.fis.com.vn/issues/?jql=status%20in%20(Open)%20AND%20project%20%3D%20BTP_GDBD_S7_2026_PM%20ORDER%20BY%20created");
        assertThat(l.projectKeys()).containsExactly("BTP_GDBD_S7_2026_PM");
    }

    @Test
    void boardAndProjectLinks() {
        assertThat(JiraLinkParser.parse("https://j.vn/secure/RapidBoard.jspa?rapidView=12&projectKey=SHOP&selectedIssue=SHOP-7")
                .projectKeys()).containsExactly("SHOP");
        assertThat(JiraLinkParser.parse("https://j.vn/projects/SHOP/issues").projectKeys()).containsExactly("SHOP");
        assertThat(JiraLinkParser.parse("https://j.vn/browse/SHOP").projectKeys()).containsExactly("SHOP");
    }

    @Test
    void homePageOnly() {
        JiraLink l = JiraLinkParser.parse("https://jira.fis.com.vn/");
        assertThat(l.baseUrl()).isEqualTo("https://jira.fis.com.vn");
        assertThat(l.projectKeys()).isEmpty();
    }

    @Test
    void rejectsNonHttp() {
        assertThatThrownBy(() -> JiraLinkParser.parse("ftp://x/y")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JiraLinkParser.parse("  ")).isInstanceOf(IllegalArgumentException.class);
    }
}
