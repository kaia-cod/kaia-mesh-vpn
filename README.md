# Kaia Mesh VPN

![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)
![Go 1.22+](https://img.shields.io/badge/Go-1.22%2B-00ADD8.svg)
![libp2p](https://img.shields.io/badge/libp2p-v0.36.5-purple.svg)
![Platforms](https://img.shields.io/badge/platforms-Android_%7C_Windows-lightgrey.svg)

> A decentralized, peer-to-peer VPN built on [libp2p](https://libp2p.io).
> Every node joins the mesh directly — **no central server, no tracker, no account**.
> Traffic is encrypted end-to-end with **Noise** and travels over TCP or QUIC,
> with NAT hole-punching built in.

---

## Table of contents

- [Why Kaia Mesh VPN](#why-kaia-mesh-vpn)
- [Features](#features)
- [Architecture](#architecture)
- [Prerequisites](#prerequisites)
- [Build](#build)
- [Running a node](#running-a-node)
- [Configuration reference](#configuration-reference)
- [Verifying two nodes really connect](#verifying-two-nodes-really-connect)
- [Running the automated end-to-end test](#running-the-automated-end-to-end-test)
- [Client applications](#client-applications)
- [Tests](#tests)
- [Protocol summary](#protocol-summary)
- [Project layout](#project-layout)
- [Status and limitations](#status-and-limitations)
- [Roadmap](#roadmap)
- [License](#license)

---

## Why Kaia Mesh VPN

Conventional VPNs funnel all of your traffic through a single company's servers:
a central point of failure, a central point of trust, and a recurring bill.
Kaia Mesh VPN takes the opposite approach.

Your device announces itself to the mesh, finds other nodes through PubSub
gossip, mDNS on the local network, or explicit bootstrap addresses, and opens a
**direct, encrypted peer link** with the node it wants to reach. If a node goes
offline, the mesh simply routes around it. There is no operator to subscribe to
and no traffic concentrator to compromise.

Typical uses: linking a handful of self-hosted machines, reaching a home lab
from a laptop, giving an Android device a stable private address on a mesh you
control.

> **Scope of v0.1.** `meshd` establishes, announces and *verifies* encrypted
> libp2p links between nodes, and ships the packet codec and routing table that
> the data path will use. IP traffic forwarding between TUN interfaces is not
> wired in yet — see [Status and limitations](#status-and-limitations).

---

## Features

- **Decentralized discovery** — peer announcements over PubSub gossip
  (`kaia-mesh/announce/1.0.0`) and mDNS on the local network.
- **End-to-end encryption** — Noise protocol (`/noise`) with TLS 1.2 fallback;
  Ed25519 node identities exposed as libp2p PeerIDs.
- **Direct, verified peer links** — explicit dialing with live connection-table
  read-back, so a link is only reported once it genuinely exists.
- **NAT traversal** — libp2p hole punching, connection manager, TCP and QUIC
  transports.
- **Multi-platform clients**
  - **Android** — Kotlin app built on `VpnService`, using a `10.42.0.0/16` TUN
    interface.
  - **Windows** — .NET 8 WinForms application plus a Windows service driving a
    Wintun adapter (`KaiaMesh`, `10.42.0.1/16`).
- **Scriptable and testable** — deterministic flags (`-connect`, `-for`,
  `-identity`) and machine-readable output lines make real connectivity
  assertions possible in CI.
- **Small core** — a single Go daemon (`meshd`) with unit tests for the packet
  codec, the routing table and configuration parsing.

---

## Architecture

```
                ┌─────────────┐   Noise / libp2p    ┌─────────────┐
     Android    │   meshd     │ ◄─────────────────► │   meshd     │    Windows
   (VpnService) │ (Go/libp2p) │    TCP · QUIC       │ (Go/libp2p) │   (Wintun)
        TUN     │  - node     │                     │  - node     │     TUN
     10.42.x.x  │  - peering  │ ◄─── DHT discovery ─►│  - peering │   10.42.x.x
                │  - tunnel   │    PubSub announce  │  - tunnel   │
                └─────────────┘                     └─────────────┘
                       ▲                                    ▲
                  mDNS (LAN)                        bootstrap peers
```

| Component | Location | Responsibility |
| --- | --- | --- |
| `meshd` daemon | `cmd/meshd/` | Runs the libp2p host, announces the node on PubSub, dials peers, reports connectivity |
| Config | `internal/config/` | Virtual IP parsing/encoding, Ed25519 identity seeds, defaults (MTU 1400), validation |
| Node | `internal/node/` | libp2p host: Noise + TLS security, NAT service, hole punching, connection manager, mDNS, bootstrap dialing |
| Peering | `internal/peering/` | Thread-safe virtual-IP → PeerID routing table |
| Tunnel | `internal/tunnel/` | Length-prefixed IP packet codec and destination-VIP extraction (library, ready to wire) |
| Android client | `android/` | `VpnService` bridge between the TUN interface and `meshd` |
| Windows client | `windows/` | WinForms UI and Windows service bridging Wintun to `meshd` |
| E2E harness | `scripts/e2e_test.sh` | Boots two nodes and asserts a live connection |

Protocol details: [`docs/PROTOCOL.md`](docs/PROTOCOL.md).

---

## Prerequisites

| Target | Requirements |
| --- | --- |
| Core daemon | Go **1.22** or newer |
| Android | Android Studio, SDK 34, device or emulator on Android 8+ (API 26) |
| Windows | Visual Studio 2022, .NET 8 SDK, Windows 10 2004+, administrator rights, Wintun driver |
| Running the E2E test | `bash`, plus the daemon built locally |

---

## Build

Build the core daemon from the repository root:

```bash
go build -o meshd ./cmd/meshd
```

Cross-compile for other platforms:

```bash
# Android (arm64 device)
GOOS=android GOARCH=arm64 CGO_ENABLED=0 go build -o meshd-android ./cmd/meshd

# Windows
GOOS=windows GOARCH=amd64 go build -o meshd.exe ./cmd/meshd
```

---

## Running a node

Every node needs a virtual IP and a persistent identity seed. On first run the
seed file is created with mode `0600`; keep it, since the seed determines the
PeerID.

```bash
./meshd \
  -vip 10.42.0.1 \
  -identity ./mesh.seed \
  -bootstrap /ip4/203.0.113.10/tcp/4001/p2p/12D3KooW...
```

Expected output:

```
meshd ready: PeerID=12D3KooW... virtualIP=10.42.0.1
listen: /ip4/192.168.1.20/tcp/53144/p2p/12D3KooW...
listen: /ip4/192.168.1.20/udp/53145/quic-v1/p2p/12D3KooW...
announced on kaia-mesh/announce/1.0.0, socket=
```

The daemon then announces itself on the `kaia-mesh/announce/1.0.0` topic and
runs until it receives `SIGINT` / `SIGTERM` (or until the `-for` window elapses).

---

## Configuration reference

All options are command-line flags on `meshd`:

| Flag | Type | Default | Description |
| --- | --- | --- | --- |
| `-vip` | string | `10.42.0.1` | Virtual IP of this node on the mesh (`10.42.x.y`). |
| `-identity` | string | *(empty)* | Path to the Ed25519 identity seed file. Created if missing; without it, the PeerID is ephemeral. |
| `-bootstrap` | string | *(empty)* | Comma-separated bootstrap multiaddrs. |
| `-connect` | string | *(empty)* | Comma-separated peer multiaddrs dialed explicitly at startup. |
| `-socket` | string | *(empty)* | Unix socket path reserved for the Android/Windows bridge. Accepted and printed at startup, but the bridge is **not implemented yet** (see Status). |
| `-for` | duration | `0` | Exit after this duration instead of running forever. `0` means run until signalled. |

Defaults are defined in `internal/config/config.go`
(listen addresses `/ip4/0.0.0.0/tcp/0` and `/ip4/0.0.0.0/udp/0/quic`, MTU 1400,
announce topic `kaia-mesh/announce/1.0.0`).

---

## Verifying two nodes really connect

`-connect` bypasses mDNS/DHT discovery by dialing explicit multiaddrs, and
`-for` bounds the run. Together they make a reproducible link test. Start node A,
then point node B at one of A's printed `listen:` lines.

```bash
# Terminal 1 — node A (announces itself, waits 25 s)
./meshd -identity a.seed -vip 10.42.0.1 -for 25s

# Terminal 2 — node B (dials A explicitly)
./meshd -identity b.seed -vip 10.42.0.2 -for 12s \
  -connect /ip4/127.0.0.1/tcp/<A-port>/p2p/<A-PeerID>
```

Node B prints machine-readable lines you can assert on:

```
CONNECTED peer=<A-PeerID> addr=/ip4/127.0.0.1/tcp/<A-port>/p2p/<A-PeerID>
PEERS count=1
LINK peer=<A-PeerID> remote=/ip4/127.0.0.1/tcp/<A-port>
```

`PEERS` is read back from the **live connection table after dialing**, not from
the dial call itself — so `count=1` together with a `LINK` line is proof the two
nodes are genuinely connected. A failed attempt prints
`DIAL-FAILED <peer>: <reason>`.

---

## Running the automated end-to-end test

```bash
./scripts/e2e_test.sh            # writes e2e-proof.log
```

The harness boots two `meshd` instances, has one dial the other, asserts a live
libp2p connection exists, and captures the full transcript in a log file. It only
ever kills the PIDs it started, so it is safe to run on a workstation. Exit code
`0` means the connection was proven.

---

## Client applications

### Android

See [`android/README.md`](android/README.md). In short: open the project in
Android Studio, cross-compile `meshd` for `arm64-v8a` into
`app/src/main/jniLibs/arm64-v8a/`, then run on a device. The system requests the
`BIND_VPN_SERVICE` permission on first activation.

### Windows

See [`windows/README.md`](windows/README.md). In short: cross-compile
`meshd.exe`, open `KaiaMesh.sln` in Visual Studio (Release), then run the GUI as
administrator or install the `KaiaMesh` service with `sc.exe create`.

---

## Tests

```bash
go vet ./...
go test ./...
```

Unit tests cover:

- the tunnel codec (`internal/tunnel`) — encode/decode round-trips and
  destination-VIP extraction;
- the peering table (`internal/peering`) — upsert, lookup, removal;
- configuration parsing (`internal/config`) — virtual IP conversion, identity
  generation.

---

## Protocol summary

| Item | Value | Implemented in v0.1 |
| --- | --- | --- |
| Announce topic | `kaia-mesh/announce/1.0.0` | Yes (published at startup) |
| Tunnel protocol | `/kaia-mesh/1.0.0` | Codec yes, stream handler no |
| Peer protocol | `/kaia-mesh/peer/1.0.0` | Reserved |
| Security | `/noise`, `/tls/1.2.0` | Yes |
| Virtual network | `10.42.0.0/16`, MTU 1400 | Addresses assigned, no forwarding |
| Identity | Ed25519 seed → libp2p PeerID | Yes (`-identity`) |
| Framing | `[4-byte big-endian length][IP packet]` | Yes (`internal/tunnel`) |

Details: [`docs/PROTOCOL.md`](docs/PROTOCOL.md).

---

## Project layout

```
cmd/meshd/          Go daemon (entry point, flags, peer dialing)
internal/config/    Configuration, virtual IP, identity seeds
internal/node/      libp2p host construction
internal/peering/   Virtual IP → PeerID routing table
internal/tunnel/    IP packet codec
android/            Kotlin VpnService client
windows/            .NET 8 WinForms app + Windows service
docs/               PROTOCOL.md, ROADMAP.md
scripts/            e2e_test.sh
```

---

## Status and limitations

**v0.1 — functional skeleton.** What works today, and is covered by the
automated two-node end-to-end test (`scripts/e2e_test.sh`):

- libp2p host with permanent Ed25519 identities, Noise (+ TLS fallback) security,
  TCP/QUIC transports, connection manager, NAT service and hole punching;
- PubSub announcement on `kaia-mesh/announce/1.0.0` at startup;
- explicit peer dialing with verified, machine-readable connectivity output
  (`CONNECTED`, `PEERS count=N`, `LINK`);
- mDNS peer discovery on the local network;
- unit-tested packet codec, routing table and configuration.

Known limitations — the README states them so nobody is misled:

- **No IP forwarding yet.** `internal/tunnel` (packet codec) and
  `internal/peering` (VIP → PeerID table) are complete and unit-tested, but the
  daemon does not yet move packets between them: `meshd` establishes and verifies
  encrypted peer links, it does not carry IP traffic end-to-end at this stage.
- **No Kademlia DHT discovery yet.** Discovery relies on PubSub, mDNS and
  explicit `-bootstrap` / `-connect` multiaddrs.
- Virtual IP allocation is **manual** (one `-vip` per host).
- `-socket` is accepted and echoed at startup, but the socket bridge between the
  Android/Windows clients and `meshd` is **not implemented yet**.
- No peer-list UI, no libp2p relay fallback for strict NAT, no security audit and
  no throughput benchmarks yet.

**Not audited for production use.** Do not send sensitive traffic over it until
an independent security review has been performed.

---

## Roadmap

See [`docs/ROADMAP.md`](docs/ROADMAP.md). Highlights: meshd ↔ client bridge over
a socket, decentralized address allocation, peer-list UI, security audit,
libp2p relay circuits, throughput benchmarks.

---

## License

MIT — see [LICENSE](LICENSE).
