using System;
using System.Drawing;
using System.Windows.Forms;

namespace KaiaMesh.App
{
    internal static class Program
    {
        [STAThread]
        static void Main()
        {
            ApplicationConfiguration.Initialize();
            Application.Run(new MainForm());
        }
    }

    public sealed class MainForm : Form
    {
        private readonly Label _status;
        private readonly Button _toggle;
        private readonly TunnelManager _tunnel = new();

        public MainForm()
        {
            Text = "Kaia Mesh VPN";
            Width = 420;
            Height = 260;
            FormBorderStyle = FormBorderStyle.FixedDialog;
            StartPosition = FormStartPosition.CenterScreen;

            _status = new Label
            {
                Text = "Tunnel stopped.",
                AutoSize = false,
                Dock = DockStyle.Top,
                Padding = new Padding(16),
                Font = new Font("Segoe UI", 11f)
            };

            _toggle = new Button
            {
                Text = "Enable tunnel",
                Dock = DockStyle.Bottom,
                Height = 48,
                Font = new Font("Segoe UI", 12f)
            };
            _toggle.Click += async (_, _) =>
            {
                _toggle.Enabled = false;
                try
                {
                    if (_tunnel.IsRunning)
                    {
                        await _tunnel.StopAsync();
                        _status.Text = "Tunnel stopped.";
                        _toggle.Text = "Enable tunnel";
                    }
                    else
                    {
                        await _tunnel.StartAsync("10.42.0.1", "255.255.0.0");
                        _status.Text = "Tunnel active — IPFS mesh (10.42.0.0/16).";
                        _toggle.Text = "Disable tunnel";
                    }
                }
                catch (Exception ex)
                {
                    _status.Text = $"Error: {ex.Message}";
                }
                finally
                {
                    _toggle.Enabled = true;
                }
            };

            Controls.Add(_toggle);
            Controls.Add(_status);
        }
    }
}
