using System.Diagnostics;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace KaiaMesh.Service;

/// <summary>
/// Windows Service host: launches meshd (Go daemon) and keeps it alive.
/// </summary>
public sealed class MeshWorker : BackgroundService
{
    private readonly ILogger<MeshWorker> _log;
    private Process? _daemon;
    private bool _disposed;

    public MeshWorker(ILogger<MeshWorker> log) => _log = log;

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        while (!stoppingToken.IsCancellationRequested)
        {
            _log.LogInformation("Starting meshd…");
            _daemon = Process.Start(new ProcessStartInfo
            {
                FileName = Path.Combine(AppContext.BaseDirectory, "meshd.exe"),
                Arguments = "--vip 10.42.0.1 --socket tcp://127.0.0.1:4242",
                UseShellExecute = false,
                CreateNoWindow = true
            });
            if (_daemon == null)
            {
                _log.LogError("meshd not found.");
                await Task.Delay(5000, stoppingToken);
                continue;
            }
            await _daemon.WaitForExitAsync(stoppingToken);
            _log.LogWarning("meshd exited (code {_daemon.ExitCode}). Restarting in 5s.", _daemon.ExitCode);
            await Task.Delay(5000, stoppingToken);
        }
    }

    public override void Dispose()
    {
        if (_disposed) return;
        try { _daemon?.Kill(); } catch { }
        _daemon?.Dispose();
        _disposed = true;
        base.Dispose();
    }
}
