using System;
using System.IO;
using System.Net.Sockets;
using System.Threading;
using System.Threading.Tasks;
using Wintun;

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

        private WintunAdapter? _adapter;
        private WintunSession? _session;
        private TcpClient? _daemon;
        private CancellationTokenSource? _cts;
        private Task? _pumpTask;

        public bool IsRunning => _adapter != null;

        public async Task StartAsync(string virtualIp, string netmask)
        {
            if (IsRunning) return;

            _adapter = new WintunAdapter(AdapterName);
            _session = _adapter.StartSession(0x400000);
            _adapter.SetIPv4(virtualIp, netmask); // configure 10.42.0.1 / 255.255.0.0

            // Connect to the meshd daemon's local bridge.
            _daemon = new TcpClient();
            await _daemon.ConnectAsync("127.0.0.1", 4242);

            _cts = new CancellationTokenSource();
            _pumpTask = Task.Run(() => PumpAsync(_cts.Token));
        }

        private async Task PumpAsync(CancellationToken ct)
        {
            var tunStream = _session!.Stream;
            var daemonStream = _daemon!.GetStream();
            var buffer = new byte[BufferSize];

            while (!ct.IsCancellationRequested)
            {
                int read = await tunStream.ReadAsync(buffer, ct);
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
