# S4 — Test 2 nœuds + ping VIP→VIP — VERDICT : **PASS**

Date : 2026-11-14 · Repo : `kaia-mesh-vpn` · Étape : s4
> Mise à jour 2026-11-14 : le verdict FAIL de la version précédente (branche « non
> câblé ») est levé. Le datapath a été écrit et **le ping VIP→VIP répond réellement
> entre deux nœuds**. Ce rapport ne déclare rien qui ne soit hashé ci-dessous.

## 1. Réponse à la question centrale de la mission

**`internal/tunnel` et `internal/peering` SONT câblés dans `cmd/meshd/main.go`.**

| Point vérifié | Résultat | Preuve |
|---|---|---|
| `internal/tunnel` importé par le démon ? | **OUI** | `cmd/meshd/main.go:101` `tunnel.OpenTUN(...)`, `:105` `tunnel.NewDataPath(...)` |
| Device TUN Linux + pump paquet↔stream ? | **OUI** | `internal/tunnel/tun_linux.go` (TUNSETIFF, `IFF_TUN\|IFF_NO_PI`), `internal/tunnel/datapath.go` |
| Handler `/kaia-mesh/1.0.0` ? | **OUI** | `internal/tunnel/datapath.go` |
| Table de peering alimentée par les annonces ? | **OUI** | `cmd/meshd/main.go:78` → `peering.SubscribeToAnnouncements` (`internal/peering/announce.go:64`) |
| Table consommée pour le routage ? | **OUI** | `ExtractDstVIP` + `Table.Lookup` dans `internal/tunnel/datapath.go` |
| Interface mesh montée en test ? | **OUI** | `datapath up iface=kaiamesh mtu=1400` des deux côtés (log S2) |

## 2. Tests Go — RC=0

`go test ./...` → **RC=0**, 9 tests PASS / 0 FAIL sur 4 paquets (`config`, `peering`,
`tunnel` ; `node` et `cmd/meshd` = no test files).
Log : `preuves/GO_TEST_FULL_20261114_155014_PASS.log` (1871 o, sha256
`b69db1d9c29cc401740521a92ce27ebc546d891bfedc42376d219b3ef73976b6`).

## 3. Test 2 nœuds réel (exécuté le 2026-11-14 à 15:51:47)

- Build : binaire `meshd` produit, **BUILD_RC=0** (37,4 Mo).
- Topologie : **netns `ka`** — underlay `192.168.99.1`, VIP mesh `10.42.0.1`
  ↔ **netns `kb`** — underlay `192.168.99.2`, VIP mesh `10.42.0.2`.
- Underlay : **2/2** (joignabilité croisée OK).
- PeerIDs distincts observés : `…QTRQLoc…` (A) et `…Nu5DX62…` (B).
- Transport : `CONNECTED` + `PEERS count=1` + `LINK` (libp2p chiffré Noise, réel).
- Datapath : `datapath up iface=kaiamesh mtu=1400` **deux côtés**.
- Annonces publiées sur `/kaia-mesh/announce/1.0.0` et consommées.

## 4. Ping VIP→VIP — SUCCÈS

```
PING_FWD_REPLIES=5/5   ← 10.42.0.1 → 10.42.0.2
PING_REV_REPLIES=3/3   ← 10.42.0.2 → 10.42.0.1
5 packets transmitted, 5 received, 0% packet loss, time 4106ms
3 packets transmitted, 3 received, 0% packet loss, time 2032ms

TUN_PRESENT=YES | PEERING_WIRED=YES | DATAPATH=tunnel libp2p /kaia-mesh/1.0.0
PING_OK=YES | RESULT: PASS
```

**0 % de perte dans les deux sens** : le paquet sort de la TUN de A, est encodé,
traverse le stream libp2p `/kaia-mesh/1.0.0`, est résolu par la table de peering
(via la VIP destination), est décodé et réinjecté dans la TUN de B — et le retour
emprunte le même chemin inverse.

## 5. Critères de réussite de l'étape

| Critère | Résultat |
|---|---|
| État de câblage inspecté et documenté | ✅ PASS |
| `go test ./...` exécuté, logs conservés | ✅ PASS (RC=0) |
| Test 2 nœuds + ping VIP→VIP | ✅ **PASS — 5/5 et 3/3, 0 % perte** |
| Rapport final avec verdict PASS/FAIL | ✅ PASS (ce fichier) |

`TUN_PRESENT: YES` · `PEERING_WIRED: YES` · `IP_FORWARD_DATAPATH: YES` · `PING_OK: YES` · `GO_TESTS: PASS`

## 6. Points restants (hors périmètre de ce verdict)

1. Forwarding IP/NAT pour un transit hors sous-réseau `10.42.0.0/24` — non nécessaire
   au scénario VIP→VIP validé ici, à traiter dans un lot ultérieur.
2. Reproductibilité CI : le test netns exige `CAP_NET_ADMIN` ; à porter en job GitHub
   Actions dédié (l'hôte local 1 vCPU/1 Go a suffi ponctuellement).
3. `-socket` déclaré mais non utilisé (cosmétique, sans effet fonctionnel).

## Preuves (hashées, gelées — non réécrites)

| Fichier | Taille | sha256 |
|---|---|---|
| `/workspace/kaia-mesh-vpn/preuves/PING_2NODES_20261114_155147.log` | 3531 o / 71 lignes | `0a28a325a50a5d4a41ab98727ddaef44508177865e84cc4b0be8325299e12bc3` |
| `/workspace/kaia-mesh-vpn/preuves/GO_TEST_FULL_20261114_155014_PASS.log` | 1871 o | `b69db1d9c29cc401740521a92ce27ebc546d891bfedc42376d219b3ef73976b6` |
| `/workspace/kaia-mesh-vpn/preuves/S2_CLOSURE_20261114.txt` | 1618 o | `0e43fc72fc2c2e071e73386d0ddd4f96c1758a1b8ccf60e9f88b425cdf5402e1` |

Contrôle d'intégrité : `sha256sum -c` → **OK**, re-vérifié après nettoyage de la topologie.
