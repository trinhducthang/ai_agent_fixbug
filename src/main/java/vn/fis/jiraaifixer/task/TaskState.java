package vn.fis.jiraaifixer.task;

public enum TaskState {
    QUEUED("Đang chờ", true),
    PULLING("Lấy issue & tạo nhánh", true),
    CODING("Claude đang sửa code", true),
    REVIEWING("Claude đang review", true),
    WAITING_APPROVAL("Chờ bạn duyệt", false),
    CHATTING("Claude đang xử lý yêu cầu thêm", true),
    PUSHING("Đang commit, push & tạo MR", true),
    DONE("Đã push & tạo MR", false),
    FAILED("Lỗi", false),
    CANCELLED("Đã hủy", false);

    private final String label;
    private final boolean busy;

    TaskState(String label, boolean busy) {
        this.label = label;
        this.busy = busy;
    }

    public String label() {
        return label;
    }

    public boolean busy() {
        return busy;
    }
}
