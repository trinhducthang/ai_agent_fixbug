package vn.fis.jiraaifixer.jira;

public class JiraException extends RuntimeException {

    private final int status;

    public JiraException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }

    /** Phiên Jira hết hạn / chưa cấu hình xác thực. */
    public static class Auth extends JiraException {
        public Auth(String message) {
            super(401, message);
        }
    }
}
