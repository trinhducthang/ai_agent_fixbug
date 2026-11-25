package vn.fis.jiraaifixer.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class JiraClientTest {

    @Test
    void normalizeCookieFromCurl() {
        String curl = "curl 'https://jira/x' \\\n  -H 'Accept: */*' \\\n  -b 'JSESSIONID=ABC; atlassian.xsrf.token=T|1|lin' \\\n  --data-raw 'a=b'";
        assertThat(JiraClient.normalizeCookie(curl)).isEqualTo("JSESSIONID=ABC; atlassian.xsrf.token=T|1|lin");
    }

    @Test
    void normalizeCookieVariants() {
        assertThat(JiraClient.normalizeCookie("Cookie: JSESSIONID=ABC")).isEqualTo("JSESSIONID=ABC");
        assertThat(JiraClient.normalizeCookie("  JSESSIONID=ABC  ")).isEqualTo("JSESSIONID=ABC");
        assertThat(JiraClient.normalizeCookie("ABC123")).isEqualTo("JSESSIONID=ABC123");
        assertThat(JiraClient.normalizeCookie("   ")).isNull();
    }

    @Test
    void requireKey() {
        JiraClient.requireKey("BTP_NTSPLVN_2026_PM-4022");
        assertThatThrownBy(() -> JiraClient.requireKey("../../rest/x")).isInstanceOf(IllegalArgumentException.class);
    }
}
