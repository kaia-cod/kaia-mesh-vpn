# kaia-mesh-vpn — État réel du projet

Dernière mise à jour : à compléter par la CI (commit `bf3b7f2`).

## Fait et vérifié (preuves)

| Élément | Preuve |
|---|---|
| Cœur Go/libp2p (node, peering, tunnel Noise, config, daemon `meshd`) | `go vet ./...` exit 0 ; `go test ./...` exit 0 (config, peering, tunnel ok) — local + CI |
| Lien 2 nœuds local (dial explicite `-connect`, fenêtre `-for`) | `e2e-proof.log`, test E2E dans le workflow Go |
| CI GitHub Actions : 3 workflows (Go, Android, Windows) | `.github/workflows/*.yml` poussés sur `main` @ `bf3b7f2` |
| APK intègre `libmeshd.so` arm64-v8a | assertion CI `unzip -l` sur l'APK (artefact `kaia-mesh-vpn-debug-apk`) |

## Reste manuel / non vérifiable sur l'hôte contraint (1 vCPU / ~1 Go RAM)

- Test DHT réel entre deux appareils physiques (Android + Windows) sur réseaux distincts → issue de suivi dédiée.
- Routage TUN complet de bout en bout (trafic IP réel traversant le tunnel).
- Validation de l'APK sur un appareil Android réel (installation + montée du `VpnService`).
