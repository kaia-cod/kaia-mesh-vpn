# Kaia Mesh VPN — Android

Kotlin app (SDK 34, min SDK 26 / Android 8) that bridges Android's `VpnService`
TUN interface to the `meshd` daemon (Go/libp2p) over a Unix socket, so packets
are routed through the IPFS mesh to their destination virtual IP.

## How it works

- `MeshVpnService` — foreground service that establishes the TUN interface
  (`10.42.0.1`, route `10.42.0.0/16`) and pumps packets to `meshd` over
  `/data/data/com.kaia.meshvpn/files/meshd.sock`, using the same
  4-byte big-endian length-prefixed framing as the core tunnel codec.
- Packets returning from the mesh are written back to the TUN fd.
- `MainActivity` — entry point; the system requests the `BIND_VPN_SERVICE`
  permission on first activation.

## Build

1. Install Android Studio and SDK 34.
2. Open this folder and let Gradle sync (`com.android.application` +
   `org.jetbrains.kotlin.android`, core-ktx, appcompat, Material).
3. Cross-compile `meshd` for `arm64-v8a` and place it in
   `app/src/main/jniLibs/arm64-v8a/`:

   ```bash
   GOOS=android GOARCH=arm64 go build -o meshd ./cmd/meshd
   ```

4. Run on an Android 8+ device or emulator.

## Notes

- The daemon must be started with `-socket` pointing at the same Unix socket
  path for the bridge to connect.
- Version `0.1.0` — tunnel relay is one-directional in this build
  (TUN → daemon); the return path is wired in the roadmap.
