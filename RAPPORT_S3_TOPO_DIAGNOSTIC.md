# RAPPORT S3 — Préparation binaires & topo 2 nœuds + diagnostic du blocage datapath

Date : 2026-11-14T15:10Z · Hôte : 1 vCPU / 961 Mo RAM (contraint)

## 1. État du câblage (re-vérifié ce jour)
| Élément | État | Preuve |
|---|---|---|
| `internal/tunnel` importé par `cmd/meshd/main.go` | **NON** (aucun hit hors `_test.go`) | `grep -rn internal/tunnel --include=*.go` → RC=1 |
| `internal/peering` importé | OUI (main.go:23) mais table inerte (NewTable + 1 Upsert) | main.go:72-73 |
| Device TUN créé par le démon | NON | `ip -o link show \| grep tun` → vide |
| VIP 10.42.0.x assignée à une interface | NON | `ip -4 addr` → ni 10.42.0.1 ni .2 |
| Route 10.42.0.0/24 | NON | aucune |
| Flag `-socket` | déclaré, jamais consommé | main.go:31 vs `socket=` vide dans les logs |

## 2. Ce qui MARCHE (outillage prêt pour S4)
- **Build OK** : `go build -o /tmp/meshd_s3 ./cmd/meshd` → BUILD_RC=0, binaire ELF 37 421 104 o (`preuves/BUILD_S3_20261114.log`).
- **Topo 2 nœuds réutilisable** : `scripts/e2e_test.sh` (MESHD_BIN surchargeable), node B listener + node A dial, auto-nettoyage sans `pkill`.
- **Test 2 nœuds exécuté** : **PASS 7/7** — connexion libp2p chiffrée réelle établie entre 10.42.0.1 (A) et 10.42.0.2 (B), `CONNECTED`, `PEERS count=1`, `LINK ... remote=/ip4/127.0.0.1/tcp/43637` (`preuves/E2E_2NODES_S3_20261114.log`, 1535 o, E2E_RC=0).
- `/dev/net/tun` existe (`crw-rw-rw- 10,200`) → la contrainte n'est **pas** matérielle mais une absence de privilège effectif (uid 1000) **et surtout** de code.

## 3. Cause racine du blocage ping VIP→VIP
Deux nœuds échangent un tunnel libp2p mais **aucun paquet IP n'y circule**, car il manque trois briques non écrites :
1. **Device TUN userspace** (création `/dev/net/tun`, IFF_TUN|IFF_NO_PI, lecture/écriture) — absent de `internal/tunnel` qui ne contient que `codec.go` (Encode/Decode/ExtractDstVIP).
2. **Binding stream libp2p ↔ TUN** : ouverture du stream `/kaia-mesh/1.0.0` par destination, pump bidirectionnel paquet↔frame. N'existe que dans `loopback_test.go`.
3. **Routeur + table de pair** : résolution `ExtractDstVIP` → `table.Lookup(dstVIP)` → peer, alimentée par `topic.Subscribe` (annonces) ; ajout de route `10.42.0.0/24 dev tunX` et assignation de la VIP.

## 4. Résultat du ping réel
`ping -c 2 10.42.0.2` (node B actif, annoncé sur le topic) → **2 transmis, 0 reçus, 100 % perte**, aucune interface tun créée pendant le run.
Log : `preuves/PING_VIP_S3_20261114.log` (1507 o).

## 5. Verdict S3
**PASS pour la préparation** (build + topo 2 nœuds + preuve de connectivité = acquisition réussie des prérequis de S4).
**FAIL pour le forwarding IP** : datapath VIP↔VIP absent — cause racine = code manquant (TUN + binding stream + routeur/route), pas le matériel.

## 6. Chemin débloquant (pour S4/suivant)
- `internal/tunnel/tun_linux.go` : `CreateTUN`, `Read/Write` (build tag linux, fallback wasm/windows).
- `internal/tunnel/pump.go` : `Pump(tun, host, table)` avec `host.NewStream(peer, proto)` + `codec`.
- `main.go` : si `-vip` et root → créer tun, `ip addr add`, `ip route add 10.42.0.0/24 dev`, lancer `Pump`, et `topic.Subscribe` pour remplir `peering.Table`.
- Sans root, tester en mode « socket local » (le flag `-socket` déjà déclaré) pour valider le pump sans privilège.
