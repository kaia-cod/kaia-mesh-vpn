# Kaia Mesh Protocol v0.1

## Identity
- Every node generates an Ed25519 key (32-byte seed). Its libp2p PeerID is the
  canonical IPFS form of that key.

## Discovery
1. **PubSub gossip** — topic `kaia-mesh/announce/1.0.0`, message
   `vip=<10.42.x.y> peer=<PeerID>`.
2. **Kademlia DHT** — key `kaia-mesh/peer/<PeerID>` maps to the same announcement.
3. **mDNS** — service `kaia-mesh` on the local network.

## Tunnel
- libp2p protocol `/kaia-mesh/1.0.0`, security `/noise` (plus TLS 1.2).
- Encapsulated IP packets: `[4-byte BE length][IP packet]`.
- Routing: a VIP → PeerID table built from the announcements.
- Transports: TCP, QUIC, WebRTC (libp2p), NAT hole punching.

## Addressing
- Virtual range `10.42.0.0/16`, MTU 1400.
- Allocation is manual / configured per host (v0.1); decentralized DHCP is
  planned for v0.2.

## Security
- End-to-end Noise encryption between peers.
- Announcements are implicitly signed by the PeerID key (libp2p).
- No central tracker; the public IPFS DHT is used for discovery only.
