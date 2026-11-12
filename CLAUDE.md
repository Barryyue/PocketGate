@AGENTS.md

## Claude Code 專用補充

- 項目知識統一維護在 `AGENTS.md`（上面已導入），不要把內容複製到這裡；新發現的坑寫進 `AGENTS.md` 第 6 節。
- 個人記憶（`~/.claude/projects/.../memory/`）只放倉庫裡不適合記錄的內容，例如用戶習慣、本機環境。
- 運行時驗證可以在 scratchpad 中寫臨時 gRPC 客戶端（引用 `src/NetworkControl.Ipc`）調用服務，不要截屏。
