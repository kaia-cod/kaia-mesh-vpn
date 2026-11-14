# Roadmap

## v0.1 — skeleton (current)
- Go/libp2p daemon (PubSub announcements, tunnel)
- Android app (VpnService)
- Windows app + service (Wintun / .NET 8)
- Unit tests for the codec and the routing table

## v0.2
- meshd ↔ app bridge over a Unix socket (replace the local TCP relay with a
  named socket)
- Decentralized address allocation: VIP proposal over the DHT plus conflict
  resolution
- Peer list UI (Android / Windows)

## v0.3
- Security audit and codec fuzzing
- Inter-peer throughput and latency benchmarks
- libp2p relay circuits (fallback for strict NAT)
