# Kaia Mesh VPN — Windows

- **KaiaMesh.App** : interface C# (.NET 8, WinForms) — crée un adaptateur
  Wintun `KaiaMesh`, configure `10.42.0.1/16` et relaie les paquets vers
  `meshd` (TCP local 4242).
- **KaiaMesh.Service** : service Windows qui lance et surveille `meshd.exe`.

## Build

1. Installer Visual Studio 2022 + .NET 8 SDK.
2. Compiler `meshd` pour Windows (`GOOS=windows go build -o meshd.exe ./cmd/meshd`)
   et le copier dans la sortie du service.
3. Ouvrir `KaiaMesh.sln`, Release, x64.
4. Installer le service : `sc create KaiaMesh binPath= ...\KaiaMesh.Service.exe start= auto`

L'exécution requiert les droits administrateur (manifest inclus) et le
driver Wintun (inclus via le paquet NuGet `Wintun`).
