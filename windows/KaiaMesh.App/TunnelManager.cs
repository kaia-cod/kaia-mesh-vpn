using System;
using System.IO;
using System.Net.Sockets;
using System.Threading;
using System.Threading.Tasks;
using WintunBindings = Unofficial.WinTun.Wintun;

namespace KaiaMesh.App
{
    /// <summary>
    /// Creates a Wintun TUN adapter, configures the virtual IP and bridges
    /// packets to the meshd daemon (Go/libp2p) over a local TCP socket.
    /// </summary>
    public sealed class TunnelManager : IDisposable
    {
        private const string AdapterName = "KaiaMesh";
        private const int BufferSize = 65535;

        private object? _adapter;
        private object? _session;
        private TcpClient? _daemon;
        private CancellationTokenSource? _cts;
        private Task? _pumpTask;

        public bool IsRunning => _adapter != null;

        public async Task StartAsync(string virtualIp, string netmask)
        {
            if (IsRunning) return;

            // NOTE (dette D-WIN-001): the concrete Wintun binding calls
            // (create adapter, StartSession, SetIPv4) depend on the chosen
            // package's exact API surface and must be validated on a real
            // Windows runner. The reference was intentionally removed so that
            // the solution restores and builds; see docs/ETAT.md.
            _adapter = null;
            _session = null;
            await Task.Yield();
            _ = virtualIp; _ = netmask;

            // Connect to the meshd daemon's local bridge.
            _daemon = new TcpClient();
            await _daemon.ConnectAsync("127.0.0.1", 4242);

            _cts = new CancellationTokenSource();
            _pumpTask = Task.Run(() => PumpAsync(_cts.Token));
        }

        private async Task PumpAsync(CancellationToken ct)
        {
            var daemonStream = _daemon!.GetStream();
            var buffer = new byte[BufferSize];

            while (!ct.IsCancellationRequested)
            {
                // Until the Wintun session is wired (D-WIN-001), no packets are
                // read from the TUN adapter.
                await Task.Delay(250, ct);
                int read = 0;
                if (read <= 0) continue;

                // 4-byte big-endian length prefix + payload.
                var header = new byte[4];
                header[0] = (byte)(read >> 24);
                header[1] = (byte)(read >> 16);
                header[2] = (byte)(read >> 8);
                header[3] = (byte)read;
                await daemonStream.WriteAsync(header, ct);
                await daemonStream.WriteAsync(buffer.AsMemory(0, read), ct);
                await daemonStream.FlushAsync(ct);
            }
        }

        public async Task StopAsync()
        {
            _cts?.Cancel();
            if (_pumpTask != null)
            {
                try { await _pumpTask; } catch { /* shutting down */ }
            }
            _daemon?.Dispose();
            _session?.Dispose();
            _adapter?.Dispose();
            _daemon = null;
            _session = null;
            _adapter = null;
        }

        public void Dispose()
        {
            _cts?.Cancel();
            _daemon?.Dispose();
            _session?.Dispose();
            _adapter?.Dispose();
        }
    }
}
