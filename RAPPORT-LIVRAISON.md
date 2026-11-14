# Kaia Mesh VPN — Rapport de livraison

**Dépôt :** https://github.com/kaia-cod/kaia-mesh-vpn (public)
**Commit livré :** `e2defd4` — 2026-11-14T06:44:57Z
**Description :** *Decentralized peer-to-peer VPN built on libp2p (Go), for Android and Windows. Encrypted Noise tunnels, DHT discovery, no central server.*

---

## 1. Ce qui a été livré

Un VPN poste-à-poste **sans serveur central**, construit sur **libp2p** (la pile réseau d'IPFS), ciblant **Android** et **Windows**.

| Brique | Technologie | État |
|---|---|---|
| Cœur réseau | Go 1.22 + go-libp2p (Noise, Yamux, TCP/QUIC) | ✅ compilé + testé |
| Découverte de pairs | DHT Kademlia + mDNS + rendezvous | ✅ |
| Tunnel chiffré | Noise XX, adressage virtuel `10.42.0.0/16` | ✅ |
| Client Android | Kotlin, `VpnService` | ✅ squelette buildable |
| Client Windows | .NET (App + Service `MeshWorker`) | ✅ squelette buildable |
| Tests | unitaires (config, peering, tunnel) + E2E 2 nœuds | ✅ verts |

## 2. Preuves vérifiées

### Test de bout en bout (2 nœuds réels)
```
RESULT: PASS - two nodes established a real encrypted libp2p connection
7/7 assertions PASS
durée: 12,6 s
```
Assertions : PeerID annoncé · adresse d'écoute · dial réussi vers le pair ·
`PEERS count=1` · `LINK` actif · aucun `DIAL-FAILED` · seed d'identité persisté.

Log complet : `e2e-proof.log`

### Tests unitaires
```
ok  github.com/kaia-cod/kaia-mesh-vpn/internal/config   0.007s
ok  github.com/kaia-cod/kaia-mesh-vpn/internal/peering  0.008s
ok  github.com/kaia-cod/kaia-mesh-vpn/internal/tunnel   0.006s
```

### Synchronisation du dépôt distant
```
git ls-remote origin main -> e2defd48564104ff59360127874cf7a794c4e08b
git rev-parse main        -> e2defd48564104ff59360127874cf7a794c4e08b
                              ^ identiques
```

## 3. Bug corrigé pendant la mission

La suite E2E renvoyait `RESULT: FAIL` alors que **toutes les assertions
réelles passaient**. Cause : l'assertion négative `check "node A had no dial
failure" '^DIAL-FAILED' /dev/null` interrogeait `/dev/null` avec un motif
positif — elle ne pouvait mathématiquement jamais correspondre, donc échouait
toujours.

Correction (commit `e2defd4`) : branche négative explicite testant le vrai log
de node A. Résultat : 7/7 PASS.

## 4. Point d'attention — `resilient-mesh-kaia` n'est PAS un doublon

Le dossier voisin `workspace/resilient-mesh-kaia` avait été signalé comme
"dossier en double". Vérification faite : il s'agit d'un **projet Python
distinct** (BLE Mesh, WiFi Direct, fallback GSM/SIP — `apps/`, `mesh_routing/`,
`voip/`, `tests/`), poussé sur son **propre dépôt**
`kaia-cod/resilient-mesh-kaia` (dernier push 2026-11-04). Aucun fichier source
commun avec le VPN Go : 0 chevauchement.

**Il a donc été conservé volontairement** — le supprimer aurait détruit du
travail non sauvegardé ailleurs.

Le véritable "dossier imbriqué en double" (un `kaia-mesh-vpn/` à l'intérieur de
`kaia-mesh-vpn/`, contenant un `README.md` et un `go.mod` fantômes) a bien été
supprimé, au commit `fed5e24`. Vérifié : `find` ne trouve plus aucun
sous-dossier `kaia-mesh-vpn`.

## 5. État de la machine

Serveur contraint (1 vCPU / ~1 Go RAM) : toute compilation a été bornée par
`timeout`, aucun modèle lourd exécuté en local. Builds Go croisés
(`GOOS=windows`, Android) faits sans incidence sur la charge.

## 6. Prochaines étapes suggérées

1. Tester le tunnel sur deux machines physiques réelles (NAT, routeurs différents).
2. Implémenter le routage IP complet dans `VpnService` (TUN → libp2p stream).
3. Ajouter un service système Windows (déjà ébauché dans `MeshWorker.cs`).
4. Signature des binaires Android (keystore) et Windows (certificat).
