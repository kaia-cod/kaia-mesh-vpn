# Kaia Mesh VPN — Android

Application Kotlin utilisant `VpnService`. Elle crée l'interface TUN
(`10.42.0.0/16`) et relaie les paquets vers le daemon `meshd`
(Go/libp2p, socket Unix) qui les route dans le mesh IPFS.

## Build

1. Installer Android Studio + SDK 34.
2. Ouvrir ce dossier, Gradle sync.
3. Compiler `meshd` pour `arm64-v8a` et le placer dans `app/src/main/jniLibs/arm64-v8a/`.
4. Lancer sur un appareil Android 8+.

Le permission-bouclier `BIND_VPN_SERVICE` est demandé par le système
lors de la première activation.
