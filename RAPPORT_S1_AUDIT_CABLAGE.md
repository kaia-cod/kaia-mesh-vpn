# RAPPORT S1 — Audit câblage main.go vs internal/{tunnel, peering}

**Date** : 2026-11-14 · **Repo** : `kaia-mesh-vpn` · **HEAD de base** : `e2092ab` + lot datapath (non encore commité)
**Verdict global : PASS (câblé — datapath vérifié de bout en bout)**

> Mise à jour 2026-11-14 : l'audit initial concluait FAIL (« non câblé »). Le lot de
> remédiation décrit au §4 a été écrit, compilé, testé et **prouvé par un ping VIP→VIP
> réel entre deux nœuds** (§3). Le verdict passe donc FAIL → PASS sur preuve, pas sur
> déclaration.

## Critères de réussite

| Critère | Résultat | Preuve |
|---|---|---|
| État de câblage inspecté et documenté | ✅ | `preuves/AUDIT_WIRING_S1_20261114.txt` (85 lignes, 4659 o, sha256 `a76fc5d169b798d16cc555bdb5b50c01c38d80dcd3fae4d9b27969fc890127f1`) |
| `go test ./...` exécuté, logs conservés | ✅ **RC=0** — 9 tests PASS / 4 paquets | `/workspace/kaia-mesh-vpn/preuves/GO_TEST_FULL_20261114_155014_PASS.log` (1871 o, sha256 `b69db1d9c29cc401740521a92ce27ebc546d891bfedc42376d219b3ef73976b6`) |
| Si câblé → test 2 nœuds + ping VIP | ✅ **ping VIP→VIP 5/5 + 3/3** | `/workspace/kaia-mesh-vpn/preuves/PING_2NODES_20261114_155147.log` (3531 o, sha256 `0a28a325a50a5d4a41ab98727ddaef44508177865e84cc4b0be8325299e12bc3`, `sha256sum -c` → OK) |
| Rapport final avec verdict PASS/FAIL | ✅ ce fichier | — |

## 1. État du câblage — AVANT (audit du matin)

- `internal/tunnel` : **non importé** par le démon ; package réduit à `codec.go` (`Encode`/`Decode`, `ExtractDstVIP`), aucun device TUN, aucun pump.
- `internal/peering` : importé mais **inerte** — `NewTable()` + 1 `Upsert` local, aucun `Subscribe`, `Lookup` jamais appelé hors tests.
- Forwarding IP : inexistant ; `-socket` décoratif ; TUN seulement côté Android/Windows.

## 2. État du câblage — APRÈS (vérifié dans le code, ce jour)

| Point | Résultat | Localisation |
|---|---|---|
| Device TUN Linux ouvert par le démon | ✅ `tunnel.OpenTUN(*ifaceName)` | `cmd/meshd/main.go:101` |
| Datapath TUN↔stream instancié | ✅ `tunnel.NewDataPath(h, tunDev, table, cfg.Mtu)` | `cmd/meshd/main.go:105` |
| Table de peering alimentée par les annonces | ✅ `peering.SubscribeToAnnouncements(ctx, topic, table)` | `cmd/meshd/main.go:78` → `internal/peering/announce.go:64` |
| Handler du protocole `/kaia-mesh/1.0.0` | ✅ dans `internal/tunnel/datapath.go` | pumps bidirectionnels |
| Routage paquet → pair | ✅ `ExtractDstVIP` + `peering.Table.Lookup` | `internal/tunnel/datapath.go` |
| Interface observée sur les nœuds en test | ✅ `datapath up iface=kaiamesh mtu=1400` (des deux côtés) | log de preuve S2 |

## 3. Bug de production trouvé et corrigé pendant ce lot

`internal/peering/announce.go` — `Announcement.Format()` sérialisait le PeerID avec
`fmt.Sprintf("%s", a.PeerID)`. Or `peer.ID` est une `string` Go portant un **multihash
binaire** : `%s` recrachait les octets bruts, que `String()` ré-encode ensuite en CID
base32 (`CovLVG4fQcqRWNEFnSjNFNZX…`, 71 car.), forme que `ParseAnnouncement` rejette
(`invalid cid: illegal base32 data at input byte 46`).

**Conséquence réelle** : toute annonce publiée était illisible par les autres nœuds →
la table de peering ne se peuplait jamais, donc aucun pair n'était routable. Ce n'était
pas un défaut de test : c'était la désynchronisation même du mesh.
**Correctif** : `a.PeerID.String()` (base58 canonique `12D3KooW…`) + test de
non-régression `TestFormatEmitsCanonicalPeerID`.

## 4. Tests & build (verts)

```
go vet ./...              → RC=0
go build ./cmd/meshd      → RC=0 (binaire 37,4 Mo)
go test ./...             → RC=0
  ok internal/config   0.003s
  ok internal/peering  0.006s   (TestAnnouncementRoundTrip, TestFormatEmitsCanonicalPeerID,
                                 TestParseAnnouncementRejectsGarbage, TestTable)
  ok internal/tunnel   0.035s   (TestCodecRoundTrip, TestLoopbackEncryptedHandshake)
  internal/node, cmd/meshd : no test files
```

## 5. Prochain lot (désormais non bloquant)

1. `-socket` reste déclaré mais non utilisé — à câbler ou retirer (cosmétique).
2. Écriture du forwarding IP/NAT pour le transit inter-VIP hors sous-réseau mesh.
3. Test 2 nœuds en CI GitHub Actions (netns nécessite `CAP_NET_ADMIN` ; l'hôte local
   1 vCPU/1 Go a suffi au test manuel S2 mais reste fragile pour un run reproductible).

## Preuves (hashées, gelées)

| Fichier | Taille | sha256 |
|---|---|---|
| `preuves/AUDIT_WIRING_S1_20261114.txt` | 4659 o | `a76fc5d169b798d16cc555bdb5b50c01c38d80dcd3fae4d9b27969fc890127f1` |
| `preuves/GO_TEST_FULL_20261114_155014_PASS.log` | 1871 o | `b69db1d9c29cc401740521a92ce27ebc546d891bfedc42376d219b3ef73976b6` |
| `preuves/PING_2NODES_20261114_155147.log` | 3531 o | `0a28a325a50a5d4a41ab98727ddaef44508177865e84cc4b0be8325299e12bc3` |

Vérification de stabilité : `sha256sum -c` → **OK**, re-exécuté après nettoyage du test.
Aucun fichier de preuve n'a été réécrit.
