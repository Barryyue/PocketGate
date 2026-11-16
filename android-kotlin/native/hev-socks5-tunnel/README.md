# hev-socks5-tunnel（自行編譯）

`app/src/main/jniLibs/*/libhev-socks5-tunnel.so` 由上游 [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)
提交 `75dbc1e`（2.20.0）加上本目錄的補丁編譯而成。

## 補丁

- `nc-write-retry.patch`：hev 寫回 tunnel fd 遇到 `EAGAIN` 時，以 `poll(POLLOUT)` 等待後重試（最多約 100 ms），
  而不是直接回傳 `ERR_WOULDBLOCK` 丟包。本應用把 AF_UNIX SOCK_SEQPACKET socket 對交給 hev 當虛擬網卡，
  接收佇列短暫滿時原本會丟掉 DNS 回覆、SYN-ACK 與資料段。

## 編譯

```powershell
git clone --recurse-submodules https://github.com/heiher/hev-socks5-tunnel
cd hev-socks5-tunnel
git checkout 75dbc1e
git submodule update --init --recursive
git apply <repo>/android-kotlin/native/hev-socks5-tunnel/nc-write-retry.patch
<ndk>/ndk-build NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=./Android.mk NDK_APPLICATION_MK=./Application.mk "APP_ABI=arm64-v8a x86_64" -j8
# 複製 libs/<abi>/libhev-socks5-tunnel.so 到 app/src/main/jniLibs/<abi>/
```

開發機使用 NDK 30.0.16248370；在中國大陸網路下 clone 時可能需要暫時繞過系統代理（見 AGENTS.md）。
升級 hev 後要重新驗證：映射 DNS 從 `100.64.0.0` 依序分配（`FakeIpMappings` 依賴這個行為）。
