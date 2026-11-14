using KaiaMesh.Service;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;

var builder = Host.CreateApplicationBuilder(args);
builder.Services.AddWindowsService(o => o.ServiceName = "KaiaMesh");
builder.Services.AddHostedService<MeshWorker>();

var host = builder.Build();
host.Run();
