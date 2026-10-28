using System;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Reflection;
using System.Text;
using System.Threading;

internal static class PortDiscoveryTests
{
    private static Type launcher;
    private static int passed;
    private const string Token = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private const string Nonce = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static void Check(bool value, string message) { if (!value) throw new Exception(message); }
    private static void Test(string name, Action body) { body(); passed++; Console.WriteLine("PASS " + name); }
    private static object Call(string name, params object[] values) { return launcher.GetMethod(name, BindingFlags.Static | BindingFlags.NonPublic).Invoke(null, values); }
    private static object Field(object value, string name) { return value.GetType().GetField(name, BindingFlags.Instance | BindingFlags.NonPublic).GetValue(value); }
    private static bool Ready(object value) { return (bool)Field(value, "Ready"); }
    private static string Identity(string directory) { return (string)Call("DataDirectoryId", directory); }
    private static string Endpoint(int port, int pid, string nonce, string dataId, string appId)
    {
        return "{\"appId\":\"" + appId + "\",\"port\":" + port + ",\"serverPid\":" + pid + ",\"instanceId\":\"" + nonce + "\",\"dataId\":\"" + dataId + "\"}";
    }
    private static void WriteEndpoint(string directory, int port, int pid, string nonce, string dataId)
    {
        File.WriteAllText(Path.Combine(directory, "instance.json"), Endpoint(port, pid, nonce, dataId, "domtrack-ua"), new UTF8Encoding(false));
    }
    private sealed class Fixture : IDisposable
    {
        private readonly TcpListener listener;
        private readonly Thread thread;
        private volatile bool stopped;
        internal readonly int Port;
        internal int Requests;
        internal string AppId = "domtrack-ua";
        internal string DataId;
        internal string InstanceId = Nonce;
        internal int Pid = 42424;
        internal bool OtherTitle;
        internal bool Stall;
        internal Fixture(int requestedPort, string dataId)
        {
            DataId = dataId; listener = new TcpListener(IPAddress.Loopback, requestedPort); listener.Start();
            Port = ((IPEndPoint)listener.LocalEndpoint).Port;
            thread = new Thread(Serve); thread.IsBackground = true; thread.Start();
        }
        private void Serve()
        {
            while (!stopped)
            {
                try
                {
                    TcpClient client = listener.AcceptTcpClient();
                    ThreadPool.QueueUserWorkItem(delegate { Respond(client); });
                }
                catch (SocketException) { if (!stopped) throw; }
                catch (ObjectDisposedException) { return; }
            }
        }
        private void Respond(TcpClient client)
        {
            using (client)
            {
                try
                {
                    NetworkStream stream = client.GetStream(); stream.ReadTimeout = 2000;
                    StringBuilder incoming = new StringBuilder(); byte[] buffer = new byte[1024]; int count;
                    while ((count = stream.Read(buffer, 0, buffer.Length)) > 0) { incoming.Append(Encoding.ASCII.GetString(buffer, 0, count)); if (incoming.ToString().Contains("\r\n\r\n")) break; if (incoming.Length > 16384) return; }
                    Interlocked.Increment(ref Requests);
                    if (Stall) { Thread.Sleep(800); return; }
                    string request = incoming.ToString(); bool status = request.StartsWith("GET /api/status ");
                    bool allowed = !status || request.IndexOf("X-App-Token: " + Token, StringComparison.OrdinalIgnoreCase) >= 0;
                    string body = status ? Endpoint(Port, Pid, InstanceId, DataId, AppId) : "<title>" + (OtherTitle ? "Different service" : "DomTrack UA - test") + "</title><meta name=\"app-token\" content=\"" + Token + "\">";
                    if (!allowed) body = "Forbidden";
                    byte[] bytes = Encoding.UTF8.GetBytes(body);
                    string header = "HTTP/1.1 " + (allowed ? "200 OK" : "403 Forbidden") + "\r\nContent-Type: " + (status ? "application/json" : "text/html") + "; charset=utf-8\r\nContent-Length: " + bytes.Length + "\r\nConnection: close\r\n\r\n";
                    byte[] headers = Encoding.ASCII.GetBytes(header); stream.Write(headers, 0, headers.Length); stream.Write(bytes, 0, bytes.Length); stream.Flush();
                }
                catch (IOException) { }
                catch (SocketException) { }
                catch (ObjectDisposedException) { }
            }
        }
        public void Dispose() { stopped = true; listener.Stop(); thread.Join(1000); }
    }
    private static int Main(string[] args)
    {
        try
        {
            if (args.Length != 2) throw new Exception("Expected launcher.exe and a named test workspace directory");
            string applicationDirectory = Path.GetDirectoryName(Path.GetFullPath(args[0]));
            AppDomain.CurrentDomain.AssemblyResolve += delegate(object sender, ResolveEventArgs resolve) {
                string name = new AssemblyName(resolve.Name).Name;
                if (name != "Microsoft.Web.WebView2.Core" && name != "Microsoft.Web.WebView2.WinForms") return null;
                string dependency = Path.Combine(applicationDirectory, "runtime", "webview2", name + ".dll");
                return File.Exists(dependency) ? Assembly.LoadFrom(dependency) : null;
            };
            Assembly assembly = Assembly.LoadFile(Path.GetFullPath(args[0])); launcher = assembly.GetType("DomTrackLauncher.LauncherContext", true);
            string root = Path.GetFullPath(args[1]); Directory.CreateDirectory(root);
            string data = Path.Combine(root, "Данные приложения с пробелами"); Directory.CreateDirectory(data);
            string id = Identity(data);
            Test("directory identity matches casing and trailing separator normalization", delegate {
                Check(id.Length == 64 && id == Identity(data.ToUpperInvariant() + Path.DirectorySeparatorChar), "Directory normalization differs");
                string drive = Path.GetPathRoot(data); Check(Identity(drive) == Identity(drive.ToUpperInvariant()), "Root identity differs");
                Check((string)Call("UrlFor", 49152) == "http://127.0.0.1:49152", "Selected port ignored");
            });
            Test("missing and oversized endpoint files are ignored", delegate {
                Check(!Ready(Call("ReadRunningIndex", data, 300)), "Missing endpoint accepted");
                File.WriteAllText(Path.Combine(data, "instance.json"), new string('x', 4097));
                Check(!Ready(Call("ReadRunningIndex", data, 300)), "Oversized endpoint accepted");
            });
            using (Fixture app = new Fixture(0, id))
            {
                Test("a matching endpoint discovers the app on its selected port", delegate {
                    WriteEndpoint(data, app.Port, app.Pid, Nonce, id);
                    object result = Call("ReadRunningIndex", data, 1500);
                    Check(Ready(result) && (int)Field(result, "Port") == app.Port && (int)Field(result, "ServerPid") == app.Pid, "App was not discovered");
                    Check((string)Field(result, "Token") == Token, "Session token missing");
                    Check(!File.ReadAllText(Path.Combine(data, "instance.json")).Contains(Token), "Session token persisted");
                });
                Test("repeated discovery reuses the running app", delegate {
                    Check(Ready(Call("ReadRunningIndex", data, 1500)), "Second discovery failed");
                    Check(app.Requests >= 4, "Status was not authenticated and verified");
                });
                Test("a PID mismatch cannot attach to a different process", delegate {
                    WriteEndpoint(data, app.Port, app.Pid + 1, Nonce, id);
                    Check(!Ready(Call("ReadRunningIndex", data, 1500)), "Wrong process attached");
                });
                Test("a stale instance nonce cannot attach to another session", delegate {
                    WriteEndpoint(data, app.Port, app.Pid, new string('c', 32), id);
                    Check(!Ready(Call("ReadRunningIndex", data, 1500)), "Stale session attached");
                });
                Test("copied endpoint metadata cannot attach from another data directory", delegate {
                    string other = Path.Combine(root, "different-data"); Directory.CreateDirectory(other);
                    WriteEndpoint(other, app.Port, app.Pid, Nonce, id);
                    int before = app.Requests;
                    Check(!Ready(Call("ReadRunningIndex", other, 1500)), "Wrong data directory attached");
                    Check(app.Requests == before, "Wrong-directory endpoint was contacted");
                });
                Test("a different app with a lookalike page is rejected and stays running", delegate {
                    WriteEndpoint(data, app.Port, app.Pid, Nonce, id); app.AppId = "different-app";
                    Check(!Ready(Call("ReadRunningIndex", data, 1500)), "Other app attached");
                    app.AppId = "domtrack-ua";
                    Check(Ready(Call("ReadRunningIndex", data, 1500)), "Foreign process was altered");
                });
                Test("invalid endpoint ports are rejected", delegate {
                    foreach (int port in new int[] { 0, -1, 65536 }) { WriteEndpoint(data, port, app.Pid, Nonce, id); Check(!Ready(Call("ReadRunningIndex", data, 300)), "Invalid port accepted"); }
                    WriteEndpoint(data, app.Port, app.Pid, Nonce, id);
                });
                Fixture busy = null;
                try { busy = new Fixture(8789, ""); busy.OtherTitle = true; }
                catch (SocketException) { /* An existing listener is left untouched. */ }
                try
                {
                    Test("an occupied legacy port does not prevent dynamic-port discovery", delegate {
                        Check(app.Port != 8789 && Ready(Call("ReadRunningIndex", data, 1500)), "Legacy port blocked selected port");
                        if (busy != null) { Check(!Ready(Call("ReadIndex", 8789, 1000)), "Foreign legacy service accepted"); Check(!Ready(Call("ReadIndex", 8789, 1000)), "Foreign service was stopped"); }
                    });
                }
                finally { if (busy != null) busy.Dispose(); }
            }
            Test("connection failures are not classified as an HTTP response", delegate {
                int unused; TcpListener listener = new TcpListener(IPAddress.Loopback, 0); listener.Start(); unused = ((IPEndPoint)listener.LocalEndpoint).Port; listener.Stop();
                object result = Call("ReadIndex", unused, 200);
                Check(!Ready(result) && !(bool)Field(result, "Responded"), "Connection failure falsely means occupied port");
            });
            Test("a timeout without response is not classified as occupied port", delegate {
                using (Fixture stalled = new Fixture(0, id)) { stalled.Stall = true; object result = Call("ReadIndex", stalled.Port, 150); Check(!Ready(result) && !(bool)Field(result, "Responded"), "Timeout falsely means occupied port"); }
            });
            Console.WriteLine("RESULT " + passed + " port discovery tests passed; no UI launched."); return 0;
        }
        catch (Exception error) { Console.Error.WriteLine("FAIL " + error); return 1; }
    }
}
