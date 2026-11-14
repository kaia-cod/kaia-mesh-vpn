# Kaia Mesh VPN

VPN décentralisé **peer-to-peer** basé sur **IPFS / libp2p**.

- Aucun serveur central : les pairs se découvrent via le DHT IPFS et se connectent en direct (NAT traversal libp2p).
- Tunnel chiffré **Noise** (libp2p-noise), routage par gossissement via **PubSub**.
- Multiplateforme : **Android** (Kotlin + VpnService) et **Windows** (C# .NET + driver Wintun).

## Architecture

```
[App Android / Windows]
        |
        v
[Daemon meshd — Go / libp2p]
  - Identité : clé Ed25519 (PeerID IPFS)
  - Découverte : Kademlia DHT + mDNS local + Peer Exchange
  - Transport : TCP / QUIC / WebRTC, Noise TLS
  - Protocoles : /kaia-mesh/1.0.0 (tunnel), /kaia-mesh/peer/1.0.0 (annonces)
        |
        v
[Interface TUN/TAP] -> trafic OS routé dans le mesh
```

## Protocole (v0.1)

1. Chaque nœud publie son annonce (PeerID, IP virtuelle `10.kaia.x.y`, pubkey) sur le topic PubSub `kaia-mesh/announce/1.0.0` et dans le DHT sous `kaia-mesh/peer/<PeerID>`.
2. Le tunnel `/kaia-mesh/1.0.0` négocie Noise, puis transporte les paquets IP encapsulés (4 octets de longueur + paquet).
3. Le routage se fait par table construite depuis les annonces : destination IP virtuelle -> PeerID.

## Structure

- `cmd/meshd/` — binaire daemon (toute plateforme)
- `internal/` — config, nœud libp2p, tunnel, peering
- `android/` — application Kotlin (VpnService <-> socket Unix du daemon)
- `windows/` — application C# WinUI 3 + service Windows (Wintun)
- `docs/` — spécifications et guides

## État

v0.1 en développement — squelette compilable et tests unitaires.

## Licence

MIT
