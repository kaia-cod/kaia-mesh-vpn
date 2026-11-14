# Kaia Mesh VPN — Windows

Windows client for [Kaia Mesh VPN](../README.md): a WinForms control panel and a
Windows service that together bridge a Wintun TUN adapter to the `meshd`
Go/libp2p daemon.

```
┌────────────────────┐        TCP 127.0.0.1:4242      ┌──────────────┐
│  KaiaMesh.App      │  ── length-prefixed IP packets ─▶│              │
│  (WinForms, admin) │                                  │   meshd.exe  │
│         │          │                                  │  (Go/libp2p) │
│    Wintun adapter  │◀─────────────────────────────────│              │
│  "KaiaMesh"        │                                  └──────┬───────┘
│  10.42.0.1/16      │                                         │
└────────────────────┘                          Noise · QUIC/TCP · DHT
                                                    to the rest of the mesh
```

## Components

| Project | Target | Role |
| --- | --- | --- |
| `KaiaMesh.App` | `net8.0-windows10.0.19041.0`, WinForms | Desktop UI. Creates and owns the `KaiaMesh` Wintun adapter, configures `10.42.0.1/16`, and pumps packets to the daemon. Requires administrator rights. |
| `KaiaMesh.Service` | `net8.0-windows`, Worker Service | Runs as the `KaiaMesh` Windows service, starts `meshd.exe` and restarts it if it exits. |

## Prerequisites

- **Windows 10 2004 (build 19041) or later**, x64.
- **.NET 8 SDK** — the app targets `net8.0-windows10.0.19041.0`, the service `net8.0-windows`.
- **Visual Studio 2022** with the *.NET desktop development* workload.
- **Go 1.22+** to cross-compile the daemon.
- **Administrator rights** — the app manifest requests `requireAdministrator`, and
  creating a Wintun adapter requires elevation.
- The **Wintun driver**, shipped through the `Wintun` NuGet package
  (`Wintun 0.1.0`), so no separate driver install is needed.

## Build

### 1. Build the daemon

From the repository root:

```powershell
$env:GOOS = "windows"; $env:GOARCH = "amd64"
go build -o windows\KaiaMesh.Service\meshd.exe .\cmd\meshd
```

Use `-ldflags "-s -w"` to strip symbols for a smaller binary.

### 2. Build the solution

```powershell
dotnet build windows\KaiaMesh.sln -c Release
```

Or open `windows\KaiaMesh.sln` in Visual Studio, select **Release | Any CPU**, and build.

> **Note on `meshd.exe` location.** `MeshWorker` starts the daemon with
> `Path.Combine(AppContext.BaseDirectory, "meshd.exe")`. After building the
> service with `dotnet build` / `dotnet publish`, copy `meshd.exe` next to
> `KaiaMesh.Service.dll` in the output folder — e.g.
> `windows\KaiaMesh.Service\bin\Release\net8.0-windows\`.

## Run the GUI

Launch `windows\KaiaMesh.App\bin\Release\net8.0-windows10.0.19041.0\KaiaMesh.exe`
and accept the UAC prompt.

1. Press **Enable tunnel**. The app creates the `KaiaMesh` Wintun adapter,
   assigns `10.42.0.1 / 255.255.0.0`, connects to the daemon on `127.0.0.1:4242`,
   and starts the packet pump. The status line reads
   *Tunnel active — IPFS mesh (10.42.0.0/16).*
2. Press **Disable tunnel** to stop the pump, close the session and release the adapter.

Any failure (daemon not running, port 4242 already bound, missing elevation) is
surfaced in the status line as `Error: …`.

## Install the service

The service keeps `meshd.exe` alive across reboots and logons, independently of
the GUI.

```powershell
cd windows\KaiaMesh.Service\bin\Release\net8.0-windows

sc.exe create KaiaMesh binPath= "$PWD\KaiaMesh.Service.exe" start= auto
sc.exe description KaiaMesh "Kaia Mesh VPN daemon supervisor"
sc.exe start KaiaMesh
```

Check status and logs:

```powershell
sc.exe query KaiaMesh
Get-WinEvent -LogName Application -MaxEvents 20 |
  Where-Object ProviderName -eq "KaiaMesh.Service"
```

Remove the service:

```powershell
sc.exe stop KaiaMesh
sc.exe delete KaiaMesh
```

The daemon is started with:

```
meshd.exe --vip 10.42.0.1 --socket tcp://127.0.0.1:4242
```

If it exits, `MeshWorker` waits 5 seconds and restarts it, logging the exit code.

## Configuration

| Setting | Where | Default |
| --- | --- | --- |
| Adapter name | `TunnelManager.AdapterName` | `KaiaMesh` |
| Virtual IP / netmask | `MainForm` → `TunnelManager.StartAsync` | `10.42.0.1` / `255.255.0.0` |
| Ring buffer size | `TunnelManager` → `StartSession` | `0x400000` (4 MiB) |
| Daemon endpoint | `TunnelManager.StartAsync` | `127.0.0.1:4242` |
| Daemon virtual IP | `MeshWorker` → `Arguments` | `10.42.0.1` |

Each node needs a **unique** virtual IP inside `10.42.0.0/16`. Allocation is
manual in this version — see the roadmap in [`docs/ROADMAP.md`](../docs/ROADMAP.md).

## Wire format

The app and the daemon exchange raw IP packets framed as:

```
┌────────────────┬──────────────────────────────┐
│ 4-byte BE len  │  IP packet (len bytes)       │
└────────────────┴──────────────────────────────┘
```

This matches the core codec in `internal/tunnel/`, so the Windows pump, the
Android bridge and `meshd` all speak the same framing.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `Error: Access is denied` | Not elevated | Run the app as administrator (the manifest should trigger UAC). |
| `Error: Connection refused` | `meshd.exe` not running or not listening on 4242 | Start the `KaiaMesh` service, or run `meshd.exe --socket tcp://127.0.0.1:4242`. |
| Adapter creation fails | Wintun driver missing | Restore the NuGet package (`dotnet restore`) and rebuild. |
| Service stops immediately | `meshd.exe` absent from the service folder | Copy `meshd.exe` next to `KaiaMesh.Service.dll`. |
| No traffic reaches the mesh | Two nodes share the same virtual IP | Give each node a distinct `10.42.x.y`. |

## Limitations (v0.1)

- The packet pump is currently **one-directional** (TUN → daemon); the return
  path is wired in the roadmap.
- Virtual-IP allocation is manual.
- The GUI only toggles the tunnel: no peer list or metrics yet.
- Not hardened for production — audit before trusting it with sensitive traffic.

## License

MIT — see [LICENSE](../LICENSE).
