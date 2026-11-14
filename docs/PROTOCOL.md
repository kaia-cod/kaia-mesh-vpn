# Protocole Kaia Mesh v0.1

## Identité
- Chaque nœud génère une clé Ed25519 (seed 32 octets). Le PeerID libp2p
  en est la forme canonique IPFS.

## Découverte
1. **PubSub gossip** : topic `kaia-mesh/announce/1.0.0`, message
   `vip=<10.42.x.y> peer=<PeerID>`.
2. **DHT Kademlia** : clé `kaia-mesh/peer/<PeerID>` -> même annonce.
3. **mDNS** : service `kaia-mesh` sur le LAN.

## Tunnel
- Protocole libp2p `/kaia-mesh/1.0.0`, sécurité `/noise` (+ TLS 1.2).
- Paquets IP encapsulés : `[4 octets BE longueur][paquet IP]`.
- Routage : table VIP -> PeerID construite depuis les annonces.
- Transports : TCP, QUIC, WebRTC (libp2p), hole punching NAT.

## Adressage
- Plage virtuelle `10.42.0.0/16`, MTU 1400.
- Allocation manuelle/configurée par hôte (v0.1) ; DHCP décentralisé = v0.2.

## Sécurité
- Chiffrement Noise de bout en bout entre pairs.
- Les annonces sont signées implicitement par la clé du PeerID (libp2p).
- Pas de traqueur central ; le DHT IPFS public sert uniquement de découverte.
