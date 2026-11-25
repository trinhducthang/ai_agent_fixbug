# Jira AI Fixer

Ứng dụng Spring Boot + HTML chạy trên máy cá nhân:

1. Hiển thị issue Jira theo JQL (mặc định: `Open`, `Reopened`, `In Progress` được giao cho bạn).
   Mỗi issue được **tự gán vào phần mềm** (qttt, ktrs, tdth, bpbd) và hiện thành badge trên danh sách.
2. Tick chọn một hoặc nhiều issue, bấm **Giao Claude fix**. Mỗi issue chạy thành một phiên riêng.
3. Trong mỗi phiên, app tạo **git worktree cho mọi repo của phần mềm** (BE + FE) trong cùng một thư mục phiên.
   **Claude Code** tự xác định lỗi nằm ở repo nào rồi sửa. Claude Code dùng CLI trong extension VS Code, với tài khoản Claude bạn đang đăng nhập.
4. Một phiên Claude khác **review độc lập** diff và sửa các lỗi chắc chắn.
5. Bạn đọc diff, chat thêm với Claude nếu cần.
6. Bấm **Commit, push & tạo MR**: app commit và push nhánh `bugfix/<KEY>`, rồi tạo merge request cho **từng repo có thay đổi**
   bằng GitLab push option.

## Phần mềm và cách tự nhận diện

| Phần mềm | Repo (worktree `be/`, `fe/`) | Nhánh nhận MR | Nhận diện |
|---|---|---|---|
| qttt | `F:/qttt/btp_ntsplvn_qttt_be`, `..._fe` | develop / develop | từ khóa: qttt, quản trị nền tảng số, PM Quản trị |
| ktrs | `F:/ktrs/btp-ntsplvn-ktrs-be`, `btp_ntsplvn_ktrs_fe` | **dev** / **main** | ktrs, kiểm tra rà soát, kiểm tra văn bản, hệ thống hóa, rà soát thường xuyên |
| tdth | `F:/tdth/btp_ntsplvn_tdth_be`, `..._fe` | develop / develop | tdth, theo dõi thi hành, tổ chức thi hành |
| bpbd | `F:/bpbd_s7/btp_bpbd_be`, `btp_bpbd_fe` (pnpm) | develop / develop | Jira project `BTP_GDBD_S7_2026_PM`; BTNN, GDBĐ |

Thứ tự nhận diện:

1. Jira project key chỉ thuộc một phần mềm sẽ được chọn ngay. Ví dụ: `BTP_GDBD_S7_2026_PM` → bpbd.
2. Nếu không, app chấm điểm từ khóa: field **Module** (`customfield_10700`) được 3 điểm, **tiêu đề** 2 điểm, **Component/Label** 1 điểm.
   Component ở Jira này thường là mục chi phí hoặc sản phẩm bàn giao, nên được tính ít điểm hơn.
3. Nếu hai phần mềm bằng điểm, hoặc không khớp gì, issue hiện "? phần mềm" và bạn chọn tay trong hộp thoại giao việc.

`btp_bpbd_ui` (SRS, tài liệu test, mockup) được đưa vào làm **tài liệu tham khảo chỉ đọc** cho các phiên bpbd.

Thêm hoặc sửa phần mềm, repo, từ khóa trong `app.projects` của `src/main/resources/application.yml`.

## Yêu cầu

- JDK 21, Maven 3.9+
- Git đã đăng nhập `git.fis.vn` (Git Credential Manager), có quyền push vào các repo.
- Claude Code (extension VS Code) đã đăng nhập. App tự tìm `claude.exe` bản mới nhất trong `~/.vscode/extensions`,
  hoặc lấy theo biến `CLAUDE_CLI_PATH`.

## Cấu hình khác

| Khóa | Ý nghĩa |
|---|---|
| `app.jira.username/password` | Basic auth Jira. Jira 6.4 không có Personal Access Token. |
| `app.jira.cookie` | Cookie phiên Jira. Có thể dán ngay trên giao diện (nút **Cookie Jira**), kể cả nguyên lệnh curl. |
| `app.jira.default-jql` | JQL mặc định. Sửa được trên giao diện. Bug trong Jira này có issue type là **Defect**. |
| `app.claude.model` | Để trống = model mặc định của tài khoản. Chọn được theo từng lần giao việc. |
| `app.claude.max-budget-usd` | Trần chi phí ước tính cho mỗi lần gọi Claude. |
| `app.claude.max-parallel` | Số phiên Claude chạy cùng lúc. Các phiên khác xếp hàng chờ. |
| `app.claude.allowed-tools` | Lệnh Claude được tự chạy. App tự thêm các lệnh git chỉ đọc theo repo (`git -C be diff`…). |

Thông tin nhạy cảm nên để trong biến môi trường (`JIRA_USERNAME`, `JIRA_PASSWORD`, `JIRA_COOKIE`) hoặc trong file
`application-local.yml` đặt cạnh nơi chạy app. File này đã có trong `.gitignore` và được nạp tự động.

## Chạy

```bat
run.cmd
```

Mở http://127.0.0.1:8099

## Cách dùng

1. Lọc issue theo **Loại** (Defect/Task…) và theo **Phần mềm**. Tick các issue cần fix, hoặc dùng "Chọn tất cả đang hiển thị".
2. Bấm **Giao Claude fix (N)**. Hộp thoại hiện phần mềm đã tự nhận diện cho từng issue; đổi được nếu sai.
   Chọn model, thêm chỉ dẫn chung nếu cần, rồi bấm **Bắt đầu**.
3. Theo dõi từng phiên ở danh sách **Phiên xử lý**. Khi phiên ở trạng thái **Chờ bạn duyệt**: đọc tab **Diff** (chia theo repo) và tab **Tóm tắt**.
4. Muốn sửa thêm thì nhắn ở ô chat. Claude tiếp tục đúng phiên đã sửa code.
5. Bấm **Commit, push & tạo MR**. Chỉ repo có thay đổi mới được commit và push. Link MR của từng repo hiện ở đầu panel.
6. Xong việc thì bấm **Xóa worktree**. Nhánh và MR trên GitLab vẫn giữ nguyên.

## An toàn

- App chỉ nghe ở `127.0.0.1` và chặn request có Host khác localhost.
- Mỗi phiên làm trong `./workspaces/<KEY>-<thời gian>/<repo>`, không đụng thư mục làm việc chính
  (kể cả khi repo gốc đang ở nhánh khác hoặc có file chưa commit).
- Claude chạy với `--strict-mcp-config`, bộ tool giới hạn và allowlist lệnh. Claude không thể commit, push, checkout
  hay `cd`. Push chỉ xảy ra khi bạn bấm nút và xác nhận.
- Nội dung issue Jira được đưa vào prompt như dữ liệu, kèm chỉ dẫn không coi đó là lệnh.
- Repo FE dùng chung `node_modules` của repo gốc qua junction. Khi xóa phiên, app gỡ junction bằng `rmdir`
  (không `/s`) trước, rồi mới `git worktree remove`.
  **Không xóa thư mục `workspaces` bằng `Remove-Item -Recurse` hay `rd /s`**: các lệnh này có thể đi theo junction
  và xóa `node_modules` thật. Hãy dùng nút **Xóa worktree**.
- Thư mục tài liệu tham khảo (`context-dirs`) chỉ được bảo vệ khỏi việc sửa bằng chỉ dẫn trong prompt,
  chưa có rule chặn kỹ thuật.

## Giới hạn

- Danh sách phiên chỉ nằm trong bộ nhớ: tắt app là mất danh sách, nhưng worktree vẫn còn trên đĩa.
  Dọn bằng `git -C <repo> worktree list` và `git worktree remove`. Với repo FE, chạy `rmdir <worktree>\node_modules` trước.
- Nếu GitLab không xử lý push option `merge_request.create`, app hiển thị link tạo MR thủ công.
- Lệnh build không có trong allowlist sẽ bị chặn. Claude ghi lại điều đó trong tóm tắt.
- Repo bpbd FE là monorepo pnpm. Package nội bộ (`packages/*`) được resolve qua `node_modules` của repo gốc,
  nên `typecheck` có thể chưa phản ánh thay đổi chéo package trong worktree.
- Đường dẫn workspace không được chứa tên ngắn 8.3 (kiểu `ABCDEF~1`). App tự chuẩn hóa về đường dẫn thật,
  vì Claude Code chặn ghi file vào đường dẫn dạng này.
