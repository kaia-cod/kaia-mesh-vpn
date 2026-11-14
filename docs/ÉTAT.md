# État du projet kaia-mesh-vpn

> Document de vérité, mis à jour le **2026-11-14**.
> Il distingue strictement trois catégories : ce qui est **fait et vérifié localement**
> (avec preuve reproductible), ce qui est **en vol et non observé**, et ce qui **reste
> manuel** (impossible à exécuter sur l'hôte de build actuel).

## 0. Résumé en une phrase

Le **cœur Go/libp2p du VPN est terminé et vérifié localement** (tests verts, tunnel
chiffré prouvé en loopback, binaires cross-compilés pour Android arm64 et Windows x64).
Les **intégrations clientes** (APK Android, application Windows) et le **test réel sur
deux appareils physiques** restent à faire : ils exigent du matériel et des SDK que
l'hôte de build (1 vCPU / 1 Go RAM) ne peut pas fournir.

---

## 1. Fait et vérifié localement

Tout ce qui suit a été exécuté sur l'hôte, avec le code retour capturé et le journal
versionné dans `docs/proofs/`. Les preuves sont sur `origin/main`.

### 1.1 Code source livré

| Élément | Détail |
|---|---|
| Dépôt | https://github.com/kaia-cod/kaia-mesh-vpn (branche `main`) |
| Commit de référence du cœur | `536af8b` — « VPN P2P libp2p: core chiffré, peering, Android + Windows » |
| Dernier commit de preuve | `86edeff` — « proof: cross-compile meshd linux/arm64 + windows/amd64 (EXIT=0) » |
| Licence | MIT |

Arborescence du cœur :

- `cmd/meshd/` — daemon : point d'entrée, chargement de config, démarrage du nœud.
- `internal/node/` — création du nœud libp2p (identité, transports, discovery).
- `internal/peering/` — table de peering, gestion des pairs, éviction.
- `internal/tunnel/` — codec du tunnel chiffré (Noise) et protocole `/kaia-mesh/1.0.0`.
- `internal/config/` — configuration typée + valeurs par défaut.
- `android/` — application Kotlin `VpnService` (TUN `10.42.0.0/16`) + pont JNI/process vers `meshd`.
- `windows/` — application C# + service Windows, solution `.sln` complète.
- `docs/PROTOCOL.md`, `docs/ROADMAP.md` — spécification du protocole et feuille de route.

### 1.2 Tests Go : `go test ./...` vert

Commande canonique du dépôt, exécutée deux fois (une fois avec cache, une fois forcée
avec `-count=1`). **Code retour `EXIT=0` dans les deux cas.**

```
?   	github.com/kaia-cod/kaia-mesh-vpn/cmd/meshd	[no test files]
ok  	github.com/kaia-cod/kaia-mesh-vpn/internal/config	0.003s
?   	github.com/kaia-cod/kaia-mesh-vpn/internal/node	[no test files]
ok  	github.com/kaia-cod/kaia-mesh-vpn/internal/peering	0.004s
ok  	github.com/kaia-cod/kaia-mesh-vpn/internal/tunnel	0.035s
EXIT=0
```

- Hôte : `go1.22.2 linux/amd64`, 1 vCPU.
- Horodatage : `2026-11-14T07:02:58Z`.
- Preuve versionnée : [`docs/proofs/go-test-all.log`](proofs/go-test-all.log).

> Note : `cmd/meshd` et `internal/node` n'ont pas de fichiers de test — c'est attendu
> (point d'entrée et câblage), pas un échec. Le comportement est couvert par le
> test-témoin de tunnel ci-dessous.

### 1.3 Test-témoin : échange chiffré réel entre deux nœuds libp2p

`TestLoopbackEncryptedHandshake` instancie **deux vrais nœuds libp2p** sur localhost,
établit une connexion Noise/XK entre eux, négocie le protocole applicatif, et fait
transiter un paquet IPv4/UDP de 60 octets dans les deux sens.

```
=== RUN   TestLoopbackEncryptedHandshake
    loopback_test.go:64: nœud A: peer=12D3KooWNoMvk8NNmig3RFje7ukj4fmnKhFfmPm9iM4XtyNUMqdx addrs=[/ip4/127.0.0.1/tcp/43323]
    loopback_test.go:65: nœud B: peer=12D3KooWKQDDLCuzYGynJPrBpEjiXaUiaZvoPjkwm7vx4RE7sXsn addrs=[/ip4/127.0.0.1/tcp/44161]
    loopback_test.go:91: connexion B -> A établie, adresse distante: /ip4/127.0.0.1/tcp/43323
    loopback_test.go:98: stream négocié sur le protocole "/kaia-mesh/1.0.0"
    loopback_test.go:115: aller-retour OK: 60 octets IPv4 identiques à travers le tunnel
    loopback_test.go:133: ciphertext/contrôle: aucun octet du payload transmis n'apparaît en clair
    loopback_test.go:135: PASS: échange chiffré Noise vérifié entre deux nœuds libp2p réels en loopback
--- PASS: TestLoopbackEncryptedHandshake (0.02s)
PASS
ok  	github.com/kaia-cod/kaia-mesh-vpn/internal/tunnel
exit_code=0
```

Ce que ce test prouve réellement :

1. Deux identités de pair distinctes sont générées et annoncées.
2. La poignée de main chiffrée Noise aboutit — pas de tunnel en clair.
3. Le protocole `/kaia-mesh/1.0.0` est bien celui qui est négocié.
4. Un datagramme IPv4 traverse intact (identiques octet pour octet) — le transport
   applicatif fonctionne, pas seulement la connexion.
5. Le payload **n'apparaît pas en clair** sur le fil : le chiffrement est effectif.

- Preuve versionnée : [`docs/proofs/witness-loopback.log`](proofs/witness-loopback.log).
- Test source : `internal/tunnel/loopback_test.go`.

### 1.4 Cross-compilation de `meshd` (sans SDK lourd)

`meshd` est du Go pur (aucune dépendance C) : `CGO_ENABLED=0` suffit, donc **ni NDK
Android ni SDK Windows ne sont nécessaires** pour produire les binaires cibles.

| Cible | Commande | Code retour | Taille | Format vérifié |
|---|---|---|---|---|
| linux/arm64 (ABI `arm64-v8a` Android) | `CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -ldflags="-s -w" -o dist/meshd-linux-arm64 ./cmd/meshd` | `EXIT_ARM64=0` | 24 314 008 octets | `ELF 64-bit LSB executable, ARM aarch64, statically linked, stripped` |
| windows/amd64 | `CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o dist/meshd-windows-amd64.exe ./cmd/meshd` | `EXIT_WINDOWS=0` | 25 612 800 octets | `PE32+ executable (console) x86-64, for MS Windows` |

Empreintes SHA-256 :

```
807c0a57b3584c9316f55752c18c068a499879f844fb9daed64e8a054b6dccda  meshd-linux-arm64
cb16b5c8e0cd031d3d04a5f7a2e66269fcc3583830daf40cf689479e953ccf67  meshd-windows-amd64.exe
```

- Preuve versionnée : [`docs/proofs/cross-compile.log`](proofs/cross-compile.log).
- Les binaires eux-mêmes ne sont pas versionnés (`dist/` est ignoré) : ils se
  reproduisent à l'identique avec les deux commandes ci-dessus.

---

## 2. En vol / non vérifié

### 2.1 Workflow GitHub Actions : non observé

Trois workflows existent (`.github/workflows/go.yml`, `android.yml`, `windows.yml`).
Ils **n'ont pas pu être observés** depuis l'hôte de build : l'outil `gh` n'y est pas
installé et aucun jeton GitHub n'y est exposé. Rien ne permet donc d'affirmer qu'ils
passent.

État connu au moment de la rédaction :

- `android.yml` échouait à l'étape « Set up Android SDK » (~7 s), avant toute
  compilation : la cause est le workflow lui-même, pas le code.
- Une correction a été poussée (`152a33d`), mais **son effet réel n'a jamais été
  constaté**.

**Conséquence honnête : la CI n'est pas prouvée verte. Elle n'est pas une preuve
d'échec non plus — elle est simplement non observée.** Elle ne doit pas être attendue
de façon bloquante : le gate réel du projet reste `go test ./...` en local (§1.2),
qui est vert avec code retour capturé.

→ Suivi : issue dédiée (voir §4).

---

## 3. Reste manuel (nécessite matériel / SDK extérieurs)

Ces trois points **ne peuvent pas être exécutés** sur l'hôte actuel (1 vCPU, 1 Go RAM,
pas de SDK Android, pas d'OS Windows, pas de second appareil physique). Ils sont
livrés sous forme de code prêt à l'emploi, mais non validés de bout en bout.

### 3.1 Intégration du binaire `meshd` dans l'APK Android

- Le code Kotlin `VpnService` (TUN `10.42.0.0/16`) et le pont vers le daemon existent.
- Le binaire arm64 se construit (§1.4) mais **n'a pas été copié dans
  `android/app/src/main/jniLibs/arm64-v8a/` ni empaqueté dans un APK**.
- **À faire** : copier le binaire, produire l'APK avec le SDK Android, l'installer
  sur un appareil, vérifier que le service démarre et que le TUN se monte.
- Pourquoi pas ici : installer le SDK Android (plusieurs Go) sur 1 Go de RAM
  asphyxierait l'hôte.

### 3.2 Compilation et exécution réelle de la solution Windows

- L'application C# + le service Windows et la solution `.sln` complète existent.
- Le binaire `meshd` windows/amd64 se construit (§1.4).
- **À faire** : ouvrir la solution dans Visual Studio / `dotnet build` sur une
  machine Windows, installer le service, vérifier l'installation de l'interface TUN
  (pilote Wintun) et le démarrage du daemon.
- Pourquoi pas ici : l'hôte est Linux, sans SDK .NET Windows ni pilote TUN Windows.

### 3.3 Routage TUN complet de bout en bout et découverte DHT réelle

- Le tunnel chiffré est prouvé en loopback (§1.3) : connexion, protocole, intégrité
  du payload, non-clair textuel.
- **Non prouvé** : (a) le routage complet des paquets du TUN de l'OS vers le tunnel
  et retour ; (b) la découverte DHT entre **deux appareils physiques distincts** sur
  des réseaux différents (NAT, relais, trous de perçage).
- **À faire** : deux appareils (ex. un Android + un PC Windows sur des réseaux
  séparés), vérifier la découverte mutuelle par DHT, puis un `ping`/HTTP à travers
  le tunnel.

---

## 4. Ce qui est demandé pour clore le projet

| # | Chantier | Type | Bloqué par |
|---|---|---|---|
| 1 | Empaqueter `meshd` arm64 dans l'APK et le tester sur appareil | Manuel | SDK Android + appareil |
| 2 | Compiler/tester la solution Windows sur un vrai poste Windows | Manuel | Poste Windows |
| 3 | Routage TUN complet + DHT entre deux appareils réels | Manuel | Deux appareils, deux réseaux |
| 4 | Rendre le workflow CI fiable et réellement observé | Technique | Accès `gh`/jeton, ou machine CI |

Les points 1 à 3 sont tracés en issues séparées sur le dépôt.

---

## 5. Comment reproduire les preuves

```bash
git clone https://github.com/kaia-cod/kaia-mesh-vpn.git
cd kaia-mesh-vpn

# 1. Tests canoniques (attendu : EXIT=0)
go test ./...

# 2. Test-témoin du tunnel chiffré (attendu : PASS)
go test ./internal/tunnel/ -run TestLoopbackEncryptedHandshake -v

# 3. Cross-compile des deux cibles (attendu : EXIT=0 chacun)
CGO_ENABLED=0 GOOS=linux   GOARCH=arm64 go build -trimpath -ldflags="-s -w" -o dist/meshd-linux-arm64      ./cmd/meshd
CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o dist/meshd-windows-amd64.exe ./cmd/meshd
```

Les journaux de référence de ces exécutions sont dans `docs/proofs/`.
