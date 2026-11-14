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
                Text = "Tunnel arrêté.",
                AutoSize = false,
                Dock = DockStyle.Top,
                Padding = new Padding(16),
                Font = new Font("Segoe UI", 11f)
            };

            _toggle = new Button
            {
                Text = "Activer le tunnel",
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
                        _status.Text = "Tunnel arrêté.";
                        _toggle.Text = "Activer le tunnel";
                    }
                    else
                    {
                        await _tunnel.StartAsync("10.42.0.1", "255.255.0.0");
                        _status.Text = "Tunnel actif — mesh IPFS (10.42.0.0/16).";
                        _toggle.Text = "Désactiver le tunnel";
                    }
                }
                catch (Exception ex)
                {
                    _status.Text = $"Erreur : {ex.Message}";
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
