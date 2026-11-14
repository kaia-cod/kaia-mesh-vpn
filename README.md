# Kaia Mesh VPN

![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)
![Go 1.22+](https://img.shields.io/badge/Go-1.22%2B-00ADD8.svg)
![libp2p](https://img.shields.io/badge/libp2p-v0.36.5-purple.svg)
![Platforms](https://img.shields.io/badge/platforms-Android_%7C_Windows-lightgrey.svg)

> A decentralized, peer-to-peer VPN built on [libp2p](https://libp2p.io) (IPFS).
> Every node joins the mesh directly — **no central server**, no tracker, no account.
> Traffic is encrypted end-to-end with **Noise** and rides on TCP, QUIC or WebRTC,
> with NAT hole-punching built in.

## Why

Classic VPNs route all of your traffic through one company's servers. Kaia Mesh
VPN takes a different approach: your device announces itself to nearby peers,
discovers other nodes over the public IPFS DHT, and opens a direct encrypted
tunnel with whoever you want to talk to. If a node goes offline, the mesh simply
routes around it.

## Features

- **Decentralized discovery** — peer announcements over PubSub gossip
  (`kaia-mesh/announce/1.0.0`), Kademlia DHT, and mDNS on the LAN.
- **End-to-end encryption** — Noise protocol (`/noise`) with TLS 1.2 fallback,
  Ed25519 node identities (libp2p PeerIDs).
- **Direct tunnels** — IP packets encapsulated as `[4-byte BE length][IP packet]`
  and routed by virtual IP (`10.42.0.0/16`, MTU 1400).
- **NAT traversal** — libp2p hole punching, connection manager, TCP + QUIC transports.
- **Multi-platform clients**
  - **Android** — Kotlin app using `VpnService` with a `10.42.0.0/16` TUN interface.
  - **Windows** — .NET 8 WinForms app + Windows Service using a Wintun adapter
    (`KaiaMesh`, `10.42.0.1/16`) and a local TCP relay on port 4242.
- **Lightweight core** — a single Go daemon (`meshd`), ~420 lines of first-party
  code plus unit tests for the packet codec and the routing table.

## Architecture

```
                ┌─────────────┐   Noise / libp2p    ┌─────────────┐
     Android    │   meshd     │ ◄─────────────────► │   meshd     │    Windows
   (VpnService) │  (Go/libp2p)│    TCP · QUIC       │  (Go/libp2p)│  (Wintun)
        TUN     │  - node     │                     │  - node     │    TUN
     10.42.x.x  │  - peering  │ ◄─── DHT discovery ─►│  - peering  │  10.42.x.x
                │  - tunnel   │    PubSub announce  │  - tunnel   │
                └─────────────┘                     └─────────────┘
                       ▲                                    ▲
                  mDNS (LAN)                        bootstrap peers
```

| Component | Location | Role |
| --- | --- | --- |
| `meshd` daemon | `cmd/meshd/` | Runs the libp2p host, announces the node, serves local apps over a socket |
| Config | `internal/config/` | Virtual IP parsing, Ed25519 identity seeds, defaults (MTU 1400) |
| Node | `internal/node/` | libp2p host: Noise security, NAT service, hole punching, mDNS |
| Peering | `internal/peering/` | Thread-safe virtual-IP → PeerID routing table |
| Tunnel | `internal/tunnel/` | Length-prefixed IP packet codec + destination-VIP extraction |

Full protocol details: [`docs/PROTOCOL.md`](docs/PROTOCOL.md).
Roadmap: [`docs/ROADMAP.md`](docs/ROADMAP.md).

## Quick start

### Prerequisites

- Go 1.22 or newer
- Android: Android Studio + SDK 34, device or emulator on Android 8+
- Windows: Visual Studio 2022 + .NET 8 SDK, administrator rights, Wintun driver

### Build the core daemon

```bash
go build -o meshd ./cmd/meshd
```

### Run a node

```bash
./meshd \
  -vip 10.42.0.1 \
  -bootstrap /ip4/<bootstrap-ip>/tcp/4001/p2p/<PeerID> \
  -identity ./mesh.seed
```

On startup the daemon prints its PeerID and virtual IP, then announces itself on
the `kaia-mesh/announce/1.0.0` topic so other nodes can reach it.

### Android

See [`android/README.md`](android/README.md): open the project in Android Studio,
cross-compile `meshd` for `arm64-v8a` into `app/src/main/jniLibs/arm64-v8a/`,
then run on device. The system requests the `BIND_VPN_SERVICE` permission on
first activation.

### Windows

See [`windows/README.md`](windows/README.md): build `meshd.exe`
(`GOOS=windows go build -o meshd.exe ./cmd/meshd`), open `KaiaMesh.sln` in
Visual Studio (Release, x64), and install the service with `sc create`.

### Tests

```bash
go test ./...
```

Unit tests cover the tunnel codec (encode/decode, VIP extraction) and the peering
table (upsert, lookup, removal).

## Protocol summary

| Item | Value |
| --- | --- |
| Announce topic | `kaia-mesh/announce/1.0.0` |
| Tunnel protocol | `/kaia-mesh/1.0.0` |
| Peer protocol | `/kaia-mesh/peer/1.0.0` |
| Security | `/noise`, `/tls/1.2.0` |
| Virtual network | `10.42.0.0/16`, MTU 1400 |
| Identity | Ed25519 key → libp2p PeerID |

## Status

**v0.1 — skeleton.** The encrypted core, peer discovery, routing table, and both
client apps are in place and unit-tested. Virtual-IP allocation is manual for
now; decentralized DHCP, a peer-list UI, relay fallback for strict NAT, a security
audit, and throughput benchmarks are on the roadmap (see `docs/ROADMAP.md`).
Not yet hardened for production use — audit before trusting it with sensitive
traffic.

## License

MIT — see [LICENSE](LICENSE).
