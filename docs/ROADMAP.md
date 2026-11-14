# Feuille de route

## v0.1 — squelette (actuel)
- Daemon Go/libp2p (annonces PubSub, tunnel)
- Application Android (VpnService)
- Application + service Windows (Wintun / .NET 8)
- Tests unitaires du codec et de la table de routage

## v0.2
- Pont Unix socket meshd <-> apps (remplacer TCP local par socket nommé)
- DH centralisé décentralisé : proposition VIP via DHT + conflit
- Interface de liste des pairs (Android/Windows)

## v0.3
- Audit de sécurité + fuzz du codec
- Bench débit/latence inter-pairs
- Relay circuits libp2p (fallback NAT strict)
