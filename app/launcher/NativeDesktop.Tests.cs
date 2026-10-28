using System;
using System.IO;
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Reflection;
using System.Text;
using System.Threading;

internal static class NativeDesktopTests
{
    private static Type window, launcher, program;
    private static int passed;
    private static readonly BindingFlags Methods = BindingFlags.NonPublic | BindingFlags.Static;
    private static object Call(Type type, string method, params object[] values) { try { return type.GetMethod(method, Methods).Invoke(null, values); } catch (TargetInvocationException e) { throw e.InnerException; } }
    private static void Check(bool condition, string message) { if (!condition) throw new Exception(message); }
    private static void Test(string name, Action action) { action(); passed++; Console.WriteLine("PASS " + name); }
    private static void Reject(Action action) { bool failed = false; try { action(); } catch (InvalidOperationException) { failed = true; } Check(failed, "Unsafe upgrade accepted"); }
    private static string Json(string value) { return "\"" + value.Replace("\\", "\\\\").Replace("\"", "\\\"") + "\""; }
    private static object Discover(string data) { return Call(launcher, "ReadRunningIndex", data, 1500); }
    private static bool Ready(object result) { return (bool)result.GetType().GetField("Ready", BindingFlags.Instance | BindingFlags.NonPublic).GetValue(result); }

    [STAThread]
    private static int Main(string[] args)
    {
        try
        {
            if (args.Length != 3) throw new Exception("Expected SDK directory, raw launcher, workspace fixture root");
            string sdk = Path.GetFullPath(args[0]), raw = Path.GetFullPath(args[1]), root = Path.GetFullPath(args[2]);
            Directory.CreateDirectory(root);
            AppDomain.CurrentDomain.AssemblyResolve += delegate(object sender, ResolveEventArgs request) { string name = new AssemblyName(request.Name).Name; if (name != "Microsoft.Web.WebView2.Core" && name != "Microsoft.Web.WebView2.WinForms") return null; return Assembly.LoadFrom(Path.Combine(sdk, name + ".dll")); };
            Assembly assembly = Assembly.LoadFile(raw); window = assembly.GetType("DomTrackLauncher.AppWindow", true); launcher = assembly.GetType("DomTrackLauncher.LauncherContext", true); program = assembly.GetType("DomTrackLauncher.Program", true);
            Test("desktop 1.3 distinguishes legacy, compatible and newer server contracts", delegate {
                Check(assembly.GetName().Version == new Version(1, 3, 0, 0), "Desktop version differs from 1.3.0");
                foreach (int api in new int[] { -1, 1, 2, 3 }) Check((int)Call(launcher, "ServerCompatibility", api) < 0, "Older API would be reused");
                Check((int)Call(launcher, "ServerCompatibility", 4) == 0, "API4 cannot be reused");
                Check((int)Call(launcher, "ServerCompatibility", 5) > 0, "Newer API would be downgraded");
            });
            Test("v4 activation is independent of an already held v3 instance", delegate {
                string identity = ((string)Call(launcher, "DataDirectoryId", Path.Combine(root, "activation"))).Substring(0, 24);
                string eventName = (string)Call(program, "ActivationName", identity), mutexName = (string)Call(program, "MutexName", identity);
                Check(eventName == @"Local\DomTrack-UA-Show-v4-" + identity && mutexName == @"Local\DomTrack-UA-Desktop-v4-" + identity, "Desktop activation contract differs");
                bool legacyCreated, currentCreated, reusedCreated;
                using (Mutex legacyMutex = new Mutex(true, @"Local\DomTrack-UA-Desktop-v3-" + identity, out legacyCreated))
                using (EventWaitHandle legacyEvent = new EventWaitHandle(false, EventResetMode.AutoReset, @"Local\DomTrack-UA-Show-v3-" + identity))
                using (Mutex currentMutex = new Mutex(true, mutexName, out currentCreated))
                using (EventWaitHandle currentEvent = new EventWaitHandle(false, EventResetMode.AutoReset, eventName))
                {
                    Check(legacyCreated && currentCreated, "Old desktop prevented the v4 upgrade launcher");
                    using (Mutex reused = new Mutex(true, mutexName, out reusedCreated)) Check(!reusedCreated, "Second API4 desktop would start another instance");
                    using (EventWaitHandle activation = EventWaitHandle.OpenExisting(eventName)) activation.Set();
                    Check(currentEvent.WaitOne(1000) && !legacyEvent.WaitOne(0), "Activation was sent to the old desktop");
                    currentMutex.ReleaseMutex(); legacyMutex.ReleaseMutex();
                }
            });
            Test("unpackaged default data keeps the existing SFX location without creating or copying files", delegate {
                string package = Path.Combine(root, "installed-package"), application = Path.Combine(package, "app"), local = Path.Combine(root, "mock-local-appdata");
                string expected = Path.Combine(local, "DomTrackUA", "data");
                Check((string)Call(launcher, "ResolveDataDirectoryAtRoots", application, null, local, null, null) == expected, "Unpackaged SFX data location changed");
                Check(!Directory.Exists(local), "Path resolution created or migrated a user folder");
            });
            Test("MSIX defaults are isolated from SFX and stable across package version paths", delegate {
                string package = Path.Combine(root, "installed-v1"), nextPackage = Path.Combine(root, "installed-v2"), local = Path.Combine(root, "mock-local-appdata"), family = "DomTrack.UA_123abc";
                string first = (string)Call(launcher, "ResolveDataDirectoryAtRoots", Path.Combine(package, "app"), null, local, package, family);
                string next = (string)Call(launcher, "ResolveDataDirectoryAtRoots", Path.Combine(nextPackage, "app"), null, local, nextPackage, family.ToUpperInvariant());
                Check(first == next && first != Path.Combine(local, "DomTrackUA", "data"), "MSIX data collides with SFX or changes between versions");
                Check(Path.GetFileName(first) == "data" && Path.GetFileName(Path.GetDirectoryName(first)).StartsWith("store-"), "MSIX data lacks a family-specific directory");
                string other = (string)Call(launcher, "ResolveDataDirectoryAtRoots", Path.Combine(package, "app"), null, local, package, "OtherApp_123abc");
                Check(first != other && !Directory.Exists(local), "Different families collide or path resolution copied data");
                Reject(delegate { Call(launcher, "ResolveDataDirectoryAtRoots", Path.Combine(package, "app"), null, local, package, null); });
            });
            Test("data overrides stay absolute and canonical while sibling package prefixes remain distinct", delegate {
                string package = Path.Combine(root, "package"), application = Path.Combine(package, "app"), local = Path.Combine(root, "mock-local-appdata"), data = Path.Combine(root, "custom", "data");
                Check((string)Call(launcher, "ResolveDataDirectoryAtRoots", application, data + Path.DirectorySeparatorChar, local, package, "DomTrack.UA_123abc") == data, "Absolute override changed");
                string sibling = package + "-other" + Path.DirectorySeparatorChar + "data";
                Check((string)Call(launcher, "ResolveDataDirectoryAtRoots", application, sibling, local, package, "DomTrack.UA_123abc") == sibling, "Sibling prefix falsely rejected");
            });
            Test("data overrides cannot point into app or any part of the readonly package", delegate {
                string package = Path.Combine(root, "package"), application = Path.Combine(package, "app"), local = Path.Combine(root, "mock-local-appdata");
                foreach (string bad in new string[] { application, Path.Combine(application, "data"), Path.Combine(package, "other-content", "data"), Path.Combine(root, "elsewhere", "..", "package", "data"), package.ToUpperInvariant() })
                    Reject(delegate { Call(launcher, "ResolveDataDirectoryAtRoots", application, bad, local, package, "DomTrack.UA_123abc"); });
            });
            Test("relative, network, device, alternate-stream and drive-root overrides fail without fallback", delegate {
                string application = Path.Combine(root, "app"), local = Path.Combine(root, "mock-local-appdata");
                foreach (string bad in new string[] { "data", @"C:data", @"\root-relative\data", @"\\server\share\data", @"\\?\C:\data", @"C:\data:stream", @"C:\" })
                    Reject(delegate { Call(launcher, "ResolveDataDirectoryAtRoots", application, bad, local, null, null); });
                Reject(delegate { Call(launcher, "ResolveDataDirectoryAtRoots", application, null, "", null, null); });
            });
            Test("WebView profile remains outside the app and package with a dedicated validated override", delegate {
                string package = Path.Combine(root, "package"), application = Path.Combine(package, "app"), data = Path.Combine(root, "user", "data"), profile = Path.Combine(root, "custom-profile");
                Check((string)Call(launcher, "ResolveProfileDirectoryAtRoots", application, data, null, package) == Path.Combine(root, "user", "webview"), "Existing SFX profile location changed");
                Check((string)Call(launcher, "ResolveProfileDirectoryAtRoots", application, data, profile, package) == profile, "Dedicated profile override ignored");
                foreach (string bad in new string[] { Path.Combine(application, "webview"), Path.Combine(package, "shared-profile"), data, Path.GetDirectoryName(data), "webview" })
                    Reject(delegate { Call(launcher, "ResolveProfileDirectoryAtRoots", application, data, bad, package); });
            });
            Test("an existing file cannot be selected as a data or profile directory", delegate {
                string application = Path.Combine(root, "app"), local = Path.Combine(root, "mock-local-appdata"), file = Path.Combine(root, "not-a-directory.txt");
                File.WriteAllText(file, "fixture-only");
                Reject(delegate { Call(launcher, "ResolveDataDirectoryAtRoots", application, file, local, null, null); });
                Reject(delegate { Call(launcher, "ResolveProfileDirectoryAtRoots", application, Path.Combine(root, "data"), file, null); });
            });
            Test("ambient or policy WebView profile overrides cannot redirect the selected profile", delegate {
                string package = Path.Combine(root, "package"), application = Path.Combine(package, "app"), profile = Path.Combine(root, "user", "webview");
                Call(window, "ValidateProfileOverride", application, profile, package, null);
                Call(window, "ValidateProfileOverride", application, profile, package, profile + Path.DirectorySeparatorChar);
                Call(window, "ValidateEffectiveProfile", application, profile, package, profile);
                foreach (string bad in new string[] { Path.Combine(root, "another-profile"), Path.Combine(package, "profile"), "profile" })
                    Reject(delegate { Call(window, "ValidateProfileOverride", application, profile, package, bad); });
                Reject(delegate { Call(window, "ValidateEffectiveProfile", application, profile, package, null); });
            });
            Test("only the exact app loopback origin can navigate inside the window", delegate {
                Uri origin = new Uri("http://127.0.0.1:43123/");
                Check((bool)Call(window, "IsApplicationUri", "http://127.0.0.1:43123/api/listings", origin), "Local app blocked");
                foreach (string bad in new string[] { "http://127.0.0.1:43124/", "http://localhost:43123/", "https://127.0.0.1:43123/", "http://127.0.0.1.evil:43123/", "file:///C:/Windows/", "javascript:alert(1)", "http://user:pass@127.0.0.1:43123/" }) Check(!(bool)Call(window, "IsApplicationUri", bad, origin), "External URI accepted");
            });
            Test("external user actions permit web and safe contact links only", delegate {
                foreach (string good in new string[] { "https://rieltor.ua/zaporozhje/flats-sale/", "tel:+380501234567", "mailto:owner%40example.invalid" }) Check((bool)Call(window, "IsExternalActionUri", good), "User action blocked");
                foreach (string bad in new string[] { "javascript:alert(1)", "file:///C:/Windows/", "shell:startup", "tel:+380501234567%0afoo", "mailto:owner@example.invalid?body=unexpected", "https://user:pass@example.com/" }) Check(!(bool)Call(window, "IsExternalActionUri", bad), "Unsafe user action accepted");
            });
            Test("private SDK loader finds installed WebView2 without opening a window", delegate {
                Assembly core = Assembly.LoadFrom(Path.Combine(sdk, "Microsoft.Web.WebView2.Core.dll"));
                Type environment = core.GetType("Microsoft.Web.WebView2.Core.CoreWebView2Environment", true);
                environment.GetMethod("SetLoaderDllFolderPath").Invoke(null, new object[] { sdk });
                string version = (string)environment.GetMethod("GetAvailableBrowserVersionString", new Type[] { typeof(string) }).Invoke(null, new object[] { null });
                Check(!String.IsNullOrEmpty(version), "Runtime unavailable"); Console.WriteLine("RUNTIME " + version);
            });
            Test("verified old backend receives one authenticated graceful shutdown", delegate {
                using (Fixture app = new Fixture(Path.Combine(root, "old"), -1)) { object index = Discover(app.Data); Check(Ready(index), "Legacy backend not discovered"); Call(launcher, "UpgradeOlderServer", app.Data, index); Check(app.Shutdowns == 1 && app.AuthenticatedShutdown, "Graceful shutdown not validated"); Check(!File.Exists(app.Endpoint), "Fixture endpoint not removed"); }
            });
            Test("running 1.1 API2 is gracefully upgraded instead of reused", delegate {
                using (Fixture app = new Fixture(Path.Combine(root, "old-api2"), 2)) { object index = Discover(app.Data); Check(Ready(index), "API2 backend not discovered"); Check((int)Call(launcher, "ServerCompatibility", 2) < 0, "API2 would be reused"); Call(launcher, "UpgradeOlderServer", app.Data, index); Check(app.Shutdowns == 1 && app.AuthenticatedShutdown && !app.ChildAlive && !File.Exists(app.Endpoint), "API2 was not completely stopped before replacement"); }
            });
            Test("running 1.2 API3 is gracefully upgraded before the native bridge contract starts", delegate {
                using (Fixture app = new Fixture(Path.Combine(root, "old-api3"), 3)) { object index = Discover(app.Data); Check(Ready(index), "API3 backend not discovered"); Check((int)Call(launcher, "ServerCompatibility", 3) < 0, "API3 would keep the old script helpers"); Call(launcher, "UpgradeOlderServer", app.Data, index); Check(app.Shutdowns == 1 && app.AuthenticatedShutdown && !app.ChildAlive && !File.Exists(app.Endpoint), "API3 was not completely stopped before replacement"); }
            });
            Test("a changed nonce is never shut down", delegate {
                using (Fixture app = new Fixture(Path.Combine(root, "nonce"), -1)) { object index = Discover(app.Data); index.GetType().GetField("InstanceId", BindingFlags.Instance | BindingFlags.NonPublic).SetValue(index, new string('b', 32)); Reject(delegate { Call(launcher, "UpgradeOlderServer", app.Data, index); }); Check(app.Shutdowns == 0 && Ready(Discover(app.Data)), "Other session altered"); }
            });
            Test("compatible API4 remains available for reuse without shutdown", delegate {
                using (Fixture app = new Fixture(Path.Combine(root, "api4"), 4)) { object index = Discover(app.Data); Check(Ready(index) && (int)Call(launcher, "ServerCompatibility", 4) == 0, "API4 cannot be reused"); Reject(delegate { Call(launcher, "UpgradeOlderServer", app.Data, index); }); Check(app.Shutdowns == 0 && app.ChildAlive && Ready(Discover(app.Data)), "Compatible server changed"); }
            });
            Test("newer API5 is never shut down or downgraded", delegate {
                using (Fixture app = new Fixture(Path.Combine(root, "api5"), 5)) { object index = Discover(app.Data); Check(Ready(index) && (int)Call(launcher, "ServerCompatibility", 5) > 0, "Newer server not rejected"); Reject(delegate { Call(launcher, "UpgradeOlderServer", app.Data, index); }); Check(app.Shutdowns == 0 && app.ChildAlive && Ready(Discover(app.Data)), "Newer server changed"); }
            });
            Test("copied metadata cannot shut down another data directory", delegate {
                using (Fixture app = new Fixture(Path.Combine(root, "original"), -1)) { object index = Discover(app.Data); string other = Path.Combine(root, "copied"); Directory.CreateDirectory(other); File.Copy(app.Endpoint, Path.Combine(other, "instance.json")); Reject(delegate { Call(launcher, "UpgradeOlderServer", other, index); }); Check(app.Shutdowns == 0 && Ready(Discover(app.Data)), "Different data directory changed"); }
            });
            Test("HTTP disappearance cannot bypass waiting for the verified old process", delegate {
                using (Fixture app = new Fixture(Path.Combine(root, "unresponsive"), -1, false)) { object index = Discover(app.Data); Check(Ready(index), "Legacy fixture not discovered"); Stopwatch timer = Stopwatch.StartNew(); Reject(delegate { Call(launcher, "UpgradeOlderServerWithTimeout", app.Data, index, 250); }); Check(timer.ElapsedMilliseconds >= 200 && app.Shutdowns == 1 && app.ChildAlive, "Old live process was treated as exited"); }
            });
            Console.WriteLine("RESULT " + passed + " native desktop checks passed; no UI, keys or user database used."); return 0;
        }
        catch (Exception error) { Console.Error.WriteLine("FAIL " + error); return 1; }
    }

    private sealed class Fixture : IDisposable
    {
        internal readonly string Data, Endpoint;
        internal int Shutdowns;
        internal bool AuthenticatedShutdown;
        private readonly TcpListener listener;
        private readonly Thread worker;
        private bool disposed;
        private readonly int port, api;
        private readonly string id;
        private readonly int Pid;
        private readonly Process legacy;
        private readonly EventWaitHandle exitSignal;
        private readonly bool exitOnShutdown;
        internal bool ChildAlive { get { return !legacy.HasExited; } }
        private static readonly string Nonce = new string('a', 32), Token = new string('c', 64);
        internal Fixture(string data, int apiVersion, bool closeAfterShutdown = true)
        {
            exitOnShutdown = closeAfterShutdown;
            string signalName = @"Local\DomTrack-Test-Legacy-" + Guid.NewGuid().ToString("N");
            exitSignal = new EventWaitHandle(false, EventResetMode.ManualReset, signalName);
            string powershell = Path.Combine(Environment.SystemDirectory, "WindowsPowerShell", "v1.0", "powershell.exe");
            string command = "[System.Threading.EventWaitHandle]::OpenExisting('" + signalName + "').WaitOne(60000) | Out-Null";
            ProcessStartInfo info = new ProcessStartInfo(powershell, "-NoProfile -NonInteractive -WindowStyle Hidden -Command \"" + command + "\"") { UseShellExecute = false, CreateNoWindow = true, WindowStyle = ProcessWindowStyle.Hidden };
            legacy = Process.Start(info); Pid = legacy.Id;
            Data = Path.GetFullPath(data); Directory.CreateDirectory(Data); Endpoint = Path.Combine(Data, "instance.json"); api = apiVersion;
            id = (string)Call(launcher, "DataDirectoryId", Data);
            listener = new TcpListener(IPAddress.Loopback, 0); listener.Start(); port = ((IPEndPoint)listener.LocalEndpoint).Port;
            File.WriteAllText(Endpoint, "{\"appId\":\"domtrack-ua\",\"port\":" + port + ",\"serverPid\":" + Pid + ",\"instanceId\":" + Json(Nonce) + ",\"dataId\":" + Json(id) + "}");
            worker = new Thread(Run); worker.IsBackground = true; worker.Start();
        }
        private void Run()
        {
            while (!disposed)
            {
                try
                {
                    using (TcpClient client = listener.AcceptTcpClient())
                    using (NetworkStream stream = client.GetStream())
                    using (StreamReader reader = new StreamReader(stream, Encoding.UTF8, false, 4096, true))
                    {
                        string first = reader.ReadLine(); if (first == null) continue; int length = 0; bool token = false; string line;
                        while (!String.IsNullOrEmpty(line = reader.ReadLine())) { if (line.StartsWith("Content-Length:", StringComparison.OrdinalIgnoreCase)) Int32.TryParse(line.Substring(15).Trim(), out length); if (line.Equals("X-App-Token: " + Token, StringComparison.OrdinalIgnoreCase)) token = true; }
                        if (length > 4096) throw new Exception("Unexpected test request size"); char[] body = new char[length]; int read = 0; while (read < length) { int count = reader.Read(body, read, length - read); if (count == 0) break; read += count; }
                        string content; bool shutdown = first.StartsWith("POST /api/shutdown ", StringComparison.Ordinal);
                        if (first.StartsWith("GET / ", StringComparison.Ordinal)) content = "<title>DomTrack UA — test</title><meta name=\"app-token\" content=\"" + Token + "\">";
                        else if (shutdown) { Shutdowns++; AuthenticatedShutdown = token && new string(body).Contains("\"serverPid\":" + Pid); content = "{\"stopping\":true}"; if (AuthenticatedShutdown) { File.Delete(Endpoint); if (exitOnShutdown) exitSignal.Set(); } }
                        else content = "{\"appId\":\"domtrack-ua\",\"serverPid\":" + Pid + ",\"instanceId\":" + Json(Nonce) + ",\"dataId\":" + Json(id) + (api >= 0 ? ",\"apiVersion\":" + api + ",\"appVersion\":\"" + (api == 2 ? "1.1.0" : api == 3 ? "1.2.0" : api == 4 ? "1.3.0" : "9.0.0") + "\"" : "") + "}";
                        byte[] payload = Encoding.UTF8.GetBytes(content); byte[] headers = Encoding.ASCII.GetBytes("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + payload.Length + "\r\nConnection: close\r\n\r\n"); stream.Write(headers, 0, headers.Length); stream.Write(payload, 0, payload.Length); stream.Flush();
                    }
                }
                catch { if (disposed) return; }
            }
        }
        public void Dispose() { disposed = true; listener.Stop(); worker.Join(1000); exitSignal.Set(); if (!legacy.WaitForExit(10000)) throw new Exception("Own test child did not exit"); legacy.Dispose(); exitSignal.Dispose(); }
    }
}
