# Kaia Mesh VPN — Windows

- **KaiaMesh.App** — C# desktop UI (.NET 8, WinForms). It creates a Wintun
  adapter named `KaiaMesh`, configures `10.42.0.1/16`, and relays packets to
  `meshd` over the local socket.
- **KaiaMesh.Service** — Windows service that starts and supervises `meshd.exe`.

## Build

1. Install Visual Studio 2022 and the .NET 8 SDK.
2. Cross-compile `meshd` for Windows
   (`GOOS=windows go build -o meshd.exe ./cmd/meshd`) and copy it into the
   service output directory.
3. Open `KaiaMesh.sln`, select Release / x64, and build.
4. Install the service:
   `sc create KaiaMesh binPath= ...\KaiaMesh.Service.exe start= auto`

Running the app and the service requires administrator rights (the manifest is
included) and the Wintun driver (pulled in through the `Wintun` NuGet package).
