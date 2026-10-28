using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Net;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Windows.Forms;

[assembly: AssemblyTitle("DomTrack UA")]
[assembly: AssemblyDescription("Локальная история цен на жильё — запуск и остановка")]
[assembly: AssemblyProduct("DomTrack UA")]
[assembly: AssemblyVersion("1.3.0.0")]
[assembly: AssemblyFileVersion("1.3.0.0")]

namespace DomTrackLauncher
{
    internal static class Program
    {
        internal static string ActivationName(string identity) { return @"Local\DomTrack-UA-Show-v4-" + identity; }
        internal static string MutexName(string identity) { return @"Local\DomTrack-UA-Desktop-v4-" + identity; }

        [System.Runtime.InteropServices.DllImport("shell32.dll", CharSet = System.Runtime.InteropServices.CharSet.Unicode)]
        private static extern int SetCurrentProcessExplicitAppUserModelID(string appId);

        [STAThread]
        private static void Main(string[] args)
        {
            if (WindowsBridge.TryRun(args)) return;
            AppDomain.CurrentDomain.AssemblyResolve += ResolveWebViewAssembly;
            Application.EnableVisualStyles(); Application.SetCompatibleTextRenderingDefault(false);
            // Packaged processes keep the AUMID assigned by the MSIX package model.
            try { if (LauncherContext.PackageInstallationDirectory() == null) SetCurrentProcessExplicitAppUserModelID("DomTrackUA.Desktop"); } catch { }
            string directory;
            try { directory = LauncherContext.ResolveDataDirectory(AppDomain.CurrentDomain.BaseDirectory, Environment.GetEnvironmentVariable("DOMTRACK_DATA_DIR")); }
            catch { MessageBox.Show("Не удалось выбрать папку данных. Нужен абсолютный локальный путь вне каталога установки, без ссылок и перенаправлений папок. Проверьте DOMTRACK_DATA_DIR.", "DomTrack UA", MessageBoxButtons.OK, MessageBoxIcon.Error); return; }
            string identity = LauncherContext.DataDirectoryId(directory).Substring(0, 24);
            bool created;
            using (EventWaitHandle activation = new EventWaitHandle(false, EventResetMode.AutoReset, ActivationName(identity)))
            using (Mutex instance = new Mutex(true, MutexName(identity), out created))
            {
                if (!created) { activation.Set(); return; }
                try { using (LauncherContext context = new LauncherContext(AppDomain.CurrentDomain.BaseDirectory, activation)) Application.Run(context); }
                catch (UserDirectoryException exception) { MessageBox.Show(exception.Message, "DomTrack UA", MessageBoxButtons.OK, MessageBoxIcon.Error); }
                catch { MessageBox.Show("Не удалось открыть DomTrack UA. Проверьте подготовку файлов и установленный Microsoft Edge WebView2 Runtime. Обычный браузер не запускается.", "DomTrack UA", MessageBoxButtons.OK, MessageBoxIcon.Error); }
                finally { instance.ReleaseMutex(); }
            }
        }

        private static Assembly ResolveWebViewAssembly(object sender, ResolveEventArgs args)
        {
            string name = new AssemblyName(args.Name).Name;
            if (name != "Microsoft.Web.WebView2.Core" && name != "Microsoft.Web.WebView2.WinForms") return null;
            string file = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "runtime", "webview2", name + ".dll");
            return File.Exists(file) ? Assembly.LoadFrom(file) : null;
        }
    }

    internal sealed class UserDirectoryException : InvalidOperationException
    {
        internal UserDirectoryException(string message) : base(message) { }
    }

    internal sealed class IndexResult
    {
        internal bool Ready;
        internal bool Responded;
        internal string Token;
        internal int Port;
        internal int ServerPid;
        internal string InstanceId;
        internal string DataId;
        internal int ApiVersion;
        internal string AppVersion;
    }

    internal sealed class LauncherContext : ApplicationContext
    {
        internal const int ApiVersion = 4;
        // Negative upgrades an older server, zero reuses this contract, positive rejects a downgrade.
        internal static int ServerCompatibility(int serverApiVersion) { return serverApiVersion.CompareTo(ApiVersion); }

        private int port;
        private string BaseUrl { get { return UrlFor(port); } }
        private readonly string root;
        private readonly string dataDirectory;
        private readonly string logPath;
        private readonly object logLock = new object();
        private readonly object stopLock = new object();
        private readonly Control dispatcher;
        private readonly NotifyIcon tray;
        private readonly Icon applicationIcon;
        private readonly RegisteredWaitHandle activationRegistration;
        private AppWindow appWindow;
        private readonly ToolStripMenuItem openItem;
        private readonly ToolStripMenuItem stopItem;
        private Process ownedProcess;
        private string ownedToken;
        private volatile bool stopping;
        private volatile bool disposed;
        private bool stopped;
        private bool stderrNoted;

        internal LauncherContext(string folder, EventWaitHandle activation)
        {
            root = Path.GetFullPath(folder);
            dataDirectory = ResolveDataDirectory(root, Environment.GetEnvironmentVariable("DOMTRACK_DATA_DIR"));
            logPath = Path.Combine(dataDirectory, "launcher.log");
            dispatcher = new Control(); dispatcher.CreateControl();
            openItem = new ToolStripMenuItem("Открыть DomTrack UA");
            openItem.Enabled = false;
            openItem.Click += delegate { OpenApplicationWindow(); };
            stopItem = new ToolStripMenuItem("Остановить и выйти");
            stopItem.Click += delegate { BeginStop(); };
            ContextMenuStrip menu = new ContextMenuStrip();
            menu.Items.Add(openItem); menu.Items.Add(new ToolStripSeparator()); menu.Items.Add(stopItem);
            try { applicationIcon = Icon.ExtractAssociatedIcon(Application.ExecutablePath) ?? (Icon)SystemIcons.Application.Clone(); }
            catch { applicationIcon = (Icon)SystemIcons.Application.Clone(); }
            tray = new NotifyIcon(); tray.Icon = applicationIcon;
            tray.Text = "DomTrack UA — запускается"; tray.ContextMenuStrip = menu; tray.Visible = true;
            tray.DoubleClick += delegate { OpenApplicationWindow(); };
            appWindow = new AppWindow(root, dataDirectory, applicationIcon);
            appWindow.FormClosing += delegate(object sender, FormClosingEventArgs args) { if (!stopping) { args.Cancel = true; BeginStop(); } };
            appWindow.Show();
            activationRegistration = ThreadPool.RegisterWaitForSingleObject(activation, delegate { Ui(delegate { OpenApplicationWindow(); }); }, null, Timeout.Infinite, false);
            Log("Запуск панели.");
            System.Windows.Forms.Timer kickoff = new System.Windows.Forms.Timer(); kickoff.Interval = 100;
            kickoff.Tick += delegate { kickoff.Stop(); kickoff.Dispose(); ThreadPool.QueueUserWorkItem(delegate { StartServer(); }); };
            kickoff.Start();
        }

        internal static string ResolveDataDirectory(string folder, string configured)
        {
            string package = PackageInstallationDirectory();
            return ResolveDataDirectoryAtRoots(folder, configured, Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), package, package == null ? null : CurrentPackageFamilyName());
        }

        // Unpackaged installs keep the SFX location. MSIX uses a separate, stable
        // family-specific path; no database is copied or migrated implicitly.
        internal static string ResolveDataDirectoryAtRoots(string folder, string configured, string localDataRoot, string packageRoot, string packageFamily)
        {
            string home = Path.Combine(CanonicalLocalDirectory(localDataRoot), "DomTrackUA");
            if (!String.IsNullOrWhiteSpace(packageRoot))
            {
                if (String.IsNullOrWhiteSpace(packageFamily) || !Regex.IsMatch(packageFamily, @"^[A-Za-z0-9][A-Za-z0-9.\-]*_[A-Za-z0-9]+$"))
                    throw new UserDirectoryException("Не удалось подтвердить семейство пакета MSIX для отдельной папки данных.");
                using (SHA256 sha = SHA256.Create())
                    home = Path.Combine(home, "store-" + BitConverter.ToString(sha.ComputeHash(Encoding.UTF8.GetBytes(packageFamily.ToLowerInvariant()))).Replace("-", "").ToLowerInvariant().Substring(0, 24));
            }
            string target = String.IsNullOrWhiteSpace(configured) ? Path.Combine(home, "data") : configured;
            return ValidateWritableDirectory(folder, packageRoot, target);
        }

        internal static string ResolveProfileDirectory(string folder, string dataDirectory, string configured)
        {
            return ResolveProfileDirectoryAtRoots(folder, dataDirectory, configured, PackageInstallationDirectory());
        }

        internal static string ResolveProfileDirectoryAtRoots(string folder, string dataDirectory, string configured, string packageRoot)
        {
            string data = ValidateWritableDirectory(folder, packageRoot, dataDirectory);
            string target = String.IsNullOrWhiteSpace(configured) ? Path.Combine(Path.GetDirectoryName(data), "webview") : configured;
            string profile = ValidateWritableDirectory(folder, packageRoot, target);
            if (SameOrInside(data, profile)) throw new UserDirectoryException("Профиль WebView2 должен находиться в отдельной папке, не содержащей базу данных.");
            return profile;
        }

        private static string CanonicalLocalDirectory(string value)
        {
            if (String.IsNullOrWhiteSpace(value) || !Regex.IsMatch(value, @"^[A-Za-z]:[\\/]") || value.IndexOf(':', 2) >= 0 || value.IndexOf('\0') >= 0 || Regex.IsMatch(value, @"(?:^|[\\/])[^\\/]*~[0-9]+(?:[\\/]|$)"))
                throw new UserDirectoryException("Укажите полный локальный путь папки, например C:\\Users\\Имя\\AppData\\Local\\DomTrackUA\\data. Сетевые, относительные и служебные пути не используются.");
            string full;
            try { full = Path.GetFullPath(value); }
            catch { throw new UserDirectoryException("Некорректный путь папки данных или профиля WebView2."); }
            string drive = Path.GetPathRoot(full);
            while (full.Length > drive.Length && (full.EndsWith("\\") || full.EndsWith("/"))) full = full.Substring(0, full.Length - 1);
            return full;
        }

        private static bool SameOrInside(string value, string directory)
        {
            return String.Equals(value, directory, StringComparison.OrdinalIgnoreCase) || value.StartsWith(directory.TrimEnd('\\', '/') + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase);
        }

        internal static string ValidateWritableDirectory(string folder, string packageRoot, string target)
        {
            string application = CanonicalLocalDirectory(folder), directory = CanonicalLocalDirectory(target);
            if (directory.Length == Path.GetPathRoot(directory).Length || SameOrInside(directory, application) || (!String.IsNullOrWhiteSpace(packageRoot) && SameOrInside(directory, CanonicalLocalDirectory(packageRoot))))
                throw new UserDirectoryException("Данные и профиль WebView2 должны находиться вне папки установленного приложения и пакета MSIX.");
            // Reject directory junctions/symlinks instead of following an alias into
            // package content. No directory is created or fallback attempted here.
            string current = directory;
            while (!String.IsNullOrEmpty(current))
            {
                if (Directory.Exists(current) || File.Exists(current))
                {
                    FileAttributes attributes = File.GetAttributes(current);
                    if ((attributes & FileAttributes.ReparsePoint) != 0 || (attributes & FileAttributes.Directory) == 0)
                        throw new UserDirectoryException("Путь данных или профиля содержит ссылку либо файл вместо папки. Выберите отдельную локальную папку.");
                }
                current = Path.GetDirectoryName(current);
            }
            return directory;
        }

        [System.Runtime.InteropServices.DllImport("kernel32.dll", CharSet = System.Runtime.InteropServices.CharSet.Unicode)]
        private static extern int GetCurrentPackagePath(ref uint length, StringBuilder path);

        [System.Runtime.InteropServices.DllImport("kernel32.dll", CharSet = System.Runtime.InteropServices.CharSet.Unicode)]
        private static extern int GetCurrentPackageFamilyName(ref uint length, StringBuilder name);

        internal static string CurrentPackageFamilyName()
        {
            uint length = 0;
            int result = GetCurrentPackageFamilyName(ref length, null);
            if (result != 122 || length < 2 || length > 1024) throw new UserDirectoryException("Не удалось определить семейство пакета MSIX.");
            StringBuilder family = new StringBuilder((int)length);
            if (GetCurrentPackageFamilyName(ref length, family) != 0) throw new UserDirectoryException("Не удалось определить семейство пакета MSIX.");
            return family.ToString();
        }

        internal static string PackageInstallationDirectory()
        {
            uint length = 0;
            int result = GetCurrentPackagePath(ref length, null);
            if (result == 15700) return null; // APPMODEL_ERROR_NO_PACKAGE: folder/SFX edition.
            if (result != 122 || length < 2 || length > 32768) throw new UserDirectoryException("Не удалось определить папку пакета MSIX.");
            StringBuilder package = new StringBuilder((int)length);
            if (GetCurrentPackagePath(ref length, package) != 0) throw new UserDirectoryException("Не удалось определить папку пакета MSIX.");
            return CanonicalLocalDirectory(package.ToString());
        }

        internal static string UrlFor(int selectedPort)
        {
            if (selectedPort < 1 || selectedPort > 65535) throw new ArgumentOutOfRangeException("selectedPort");
            return "http://127.0.0.1:" + selectedPort;
        }

        internal static string DataDirectoryId(string directory)
        {
            string normalized = Path.GetFullPath(directory);
            int rootLength = Path.GetPathRoot(normalized).Length;
            while (normalized.Length > rootLength && (normalized.EndsWith("\\") || normalized.EndsWith("/"))) normalized = normalized.Substring(0, normalized.Length - 1);
            normalized = normalized.ToLowerInvariant();
            using (SHA256 sha = SHA256.Create()) return BitConverter.ToString(sha.ComputeHash(Encoding.UTF8.GetBytes(normalized))).Replace("-", "").ToLowerInvariant();
        }

        private static HttpWebRequest Request(int requestPort, string route, string method, int timeout, string token)
        {
            HttpWebRequest request = (HttpWebRequest)WebRequest.Create(UrlFor(requestPort) + route);
            request.Method = method; request.Timeout = timeout; request.ReadWriteTimeout = timeout;
            request.Proxy = null; request.AllowAutoRedirect = false; request.KeepAlive = false;
            if (token != null) request.Headers["X-App-Token"] = token;
            return request;
        }

        private static string ReadBody(HttpWebResponse response)
        {
            using (Stream stream = response.GetResponseStream())
            using (StreamReader reader = new StreamReader(stream, Encoding.UTF8, true))
            {
                StringBuilder body = new StringBuilder(); char[] buffer = new char[4096]; int count;
                while ((count = reader.Read(buffer, 0, buffer.Length)) > 0)
                {
                    body.Append(buffer, 0, count);
                    if (body.Length > 1024 * 1024) throw new InvalidDataException();
                }
                return body.ToString();
            }
        }

        private static int NumberField(string body, string name)
        {
            Match match = Regex.Match(body, "\"" + Regex.Escape(name) + "\"\\s*:\\s*([0-9]{1,10})(?=\\s*[,}])");
            int value; return match.Success && Int32.TryParse(match.Groups[1].Value, out value) ? value : -1;
        }

        private static string HexField(string body, string name, int length)
        {
            Match match = Regex.Match(body, "\"" + Regex.Escape(name) + "\"\\s*:\\s*\"([a-f0-9]{" + length + "})\"");
            return match.Success ? match.Groups[1].Value : null;
        }

        internal static IndexResult ReadIndex(int requestPort, int timeout)
        {
            IndexResult result = new IndexResult(); result.Port = requestPort;
            try
            {
                using (HttpWebResponse response = (HttpWebResponse)Request(requestPort, "/", "GET", timeout, null).GetResponse())
                {
                    result.Responded = true;
                    if (response.StatusCode != HttpStatusCode.OK) return result;
                    string html = ReadBody(response);
                    if (!Regex.IsMatch(html, @"<title>\s*DomTrack UA(?:\s|[<—-])", RegexOptions.IgnoreCase)) return result;
                    Match token = Regex.Match(html, "<meta\\s+name=[\"']app-token[\"']\\s+content=[\"']([a-f0-9]{64})[\"']", RegexOptions.IgnoreCase);
                    if (!token.Success) return result;
                    result.Token = token.Groups[1].Value;
                }
                using (HttpWebResponse response = (HttpWebResponse)Request(requestPort, "/api/status", "GET", timeout, result.Token).GetResponse())
                {
                    if (response.StatusCode != HttpStatusCode.OK) return result;
                    string body = ReadBody(response);
                    if (!Regex.IsMatch(body, "\"appId\"\\s*:\\s*\"domtrack-ua\"")) return result;
                    result.ServerPid = NumberField(body, "serverPid");
                    result.InstanceId = HexField(body, "instanceId", 32); result.DataId = HexField(body, "dataId", 64);
                    result.ApiVersion = NumberField(body, "apiVersion");
                    Match version = Regex.Match(body, "\"appVersion\"\\s*:\\s*\"([0-9.]{1,30})\"");
                    result.AppVersion = version.Success ? version.Groups[1].Value : null;
                    result.Ready = result.ServerPid > 0 && result.InstanceId != null && result.DataId != null;
                }
            }
            catch (WebException exception)
            {
                if (exception.Response != null) { result.Responded = true; exception.Response.Close(); }
            }
            catch (IOException) { }
            catch (ArgumentException) { }
            return result;
        }

        internal static IndexResult ReadRunningIndex(string dataDirectory, int timeout)
        {
            try
            {
                string file = Path.Combine(dataDirectory, "instance.json");
                if (!File.Exists(file) || new FileInfo(file).Length > 4096) return new IndexResult();
                string body = File.ReadAllText(file, Encoding.UTF8);
                int savedPort = NumberField(body, "port"), savedPid = NumberField(body, "serverPid");
                string instanceId = HexField(body, "instanceId", 32), dataId = HexField(body, "dataId", 64);
                if (savedPort < 1 || savedPort > 65535 || savedPid < 1 || instanceId == null || dataId != DataDirectoryId(dataDirectory) || !Regex.IsMatch(body, "\"appId\"\\s*:\\s*\"domtrack-ua\"")) return new IndexResult();
                IndexResult result = ReadIndex(savedPort, timeout);
                if (result.Ready && result.ServerPid == savedPid && result.InstanceId == instanceId && result.DataId == dataId) return result;
            }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
            catch (ArgumentException) { }
            return new IndexResult();
        }

        // Compatibility is used only when an older launcher's mutex is already held.
        internal static IndexResult ReadLegacyIndex(int timeout)
        {
            IndexResult result = new IndexResult(); result.Port = 8789;
            try
            {
                using (HttpWebResponse response = (HttpWebResponse)Request(8789, "/", "GET", timeout, null).GetResponse())
                {
                    string html = ReadBody(response);
                    if (!Regex.IsMatch(html, @"<title>\s*DomTrack UA(?:\s|[<—-])", RegexOptions.IgnoreCase)) return result;
                    Match token = Regex.Match(html, "<meta\\s+name=[\"']app-token[\"']\\s+content=[\"']([a-f0-9]{64})[\"']", RegexOptions.IgnoreCase);
                    if (!token.Success) return result; result.Token = token.Groups[1].Value;
                }
                using (HttpWebResponse response = (HttpWebResponse)Request(8789, "/api/status", "GET", timeout, result.Token).GetResponse())
                {
                    if (response.StatusCode != HttpStatusCode.OK) return result;
                    string body = ReadBody(response);
                    result.Ready = NumberField(body, "serverPid") > 0 && Regex.IsMatch(body, "\"allInternet\"\\s*:\\s*false");
                }
            }
            catch (WebException) { }
            catch (IOException) { }
            return result;
        }

        private string FindNode()
        {
            string bundled = Path.Combine(root, "runtime", "node.exe");
            return File.Exists(bundled) ? bundled : null;
        }

        private static ProcessStartInfo NodeStart(string node, string arguments, string directory)
        {
            ProcessStartInfo info = new ProcessStartInfo(node, arguments);
            info.UseShellExecute = false; info.CreateNoWindow = true; info.WindowStyle = ProcessWindowStyle.Hidden;
            info.WorkingDirectory = directory; info.RedirectStandardOutput = true; info.RedirectStandardError = true;
            info.StandardOutputEncoding = Encoding.UTF8; info.StandardErrorEncoding = Encoding.UTF8;
            info.EnvironmentVariables.Remove("NODE_OPTIONS"); info.EnvironmentVariables.Remove("NODE_PATH");
            // Only the packaged launcher chooses the native bridge; the server never searches PATH.
            info.EnvironmentVariables["DOMTRACK_WINDOWS_BRIDGE"] = Application.ExecutablePath;
            return info;
        }

        private bool CheckVersion(string node)
        {
            using (Process process = new Process())
            {
                process.StartInfo = NodeStart(node, "--version", root);
                process.Start();
                if (!process.WaitForExit(5000)) { try { process.Kill(); } catch { } return false; }
                string version = process.StandardOutput.ReadToEnd().Trim();
                Match match = Regex.Match(version, @"^v([0-9]+)\."); int major;
                return process.ExitCode == 0 && match.Success && Int32.TryParse(match.Groups[1].Value, out major) && major >= 24;
            }
        }

        private void StartServer()
        {
            try
            {
                ValidateWritableDirectory(root, PackageInstallationDirectory(), dataDirectory);
                IndexResult existing = ReadRunningIndex(dataDirectory, 1200);
                if (!existing.Ready && RecordedProcessIsAlive(dataDirectory)) throw new InvalidOperationException("Ранее запущенный экземпляр ещё работает, но не отвечает. Дождитесь его завершения или корректно закройте старую программу; новый сервер не запущен, чтобы сохранить целостность базы.");
                if (existing.Ready && ServerCompatibility(existing.ApiVersion) < 0)
                {
                    Ui(delegate { appWindow.SetStatus("Обновляем работающую версию DomTrack UA. База и ключ остаются на месте…"); });
                    UpgradeOlderServer(dataDirectory, existing);
                    existing = ReadRunningIndex(dataDirectory, 1200);
                }
                if (existing.Ready)
                {
                    if (ServerCompatibility(existing.ApiVersion) > 0) throw new InvalidOperationException("Уже работает более новая версия DomTrack UA. Закройте её перед запуском этой сборки.");
                    if (ServerCompatibility(existing.ApiVersion) != 0) throw new InvalidOperationException("Не удалось обновить работающую версию. Корректно закройте старую программу и повторите запуск.");
                    port = existing.Port;
                    Log("Найден уже работающий DomTrack UA. Панель не получает права его остановки.");
                    Ui(delegate { stopItem.Text = "Закрыть панель"; Ready(); });
                    return;
                }
                string server = Path.Combine(root, "server.cjs");
                if (!File.Exists(server) || !File.Exists(Path.Combine(root, "public", "index.html"))) throw new InvalidOperationException("Не найдены файлы приложения. Распакуйте весь архив перед запуском.");
                string node = FindNode();
                if (node == null || !CheckVersion(node)) throw new InvalidOperationException("Не найден рабочий Node.js 24 или новее. В полной сборке должен быть файл runtime\\node.exe.");
                lock (stopLock)
                {
                    if (stopping) return;
                    Process child = new Process();
                    child.StartInfo = NodeStart(node, "\"" + server + "\"", root);
                    child.StartInfo.EnvironmentVariables["DOMTRACK_PORT"] = "0";
                    child.StartInfo.EnvironmentVariables["DOMTRACK_DESKTOP"] = "1";
                    child.StartInfo.EnvironmentVariables["DOMTRACK_DATA_DIR"] = dataDirectory;
                    child.OutputDataReceived += delegate { /* Drain output without storing possible private server data. */ };
                    child.ErrorDataReceived += delegate(object sender, DataReceivedEventArgs args) { if (args.Data != null && !stderrNoted) { stderrNoted = true; Log("Сервер выдал диагностическое сообщение; его содержимое не записывается в журнал панели."); } };
                    child.Start(); ownedProcess = child;
                    child.BeginOutputReadLine(); child.BeginErrorReadLine();
                    child.EnableRaisingEvents = true;
                    child.Exited += delegate { if (!stopping) { Log("Сервер завершился."); Ui(delegate { if (!stopping) { ShowError("Локальный сервер остановился. Перезапустите DomTrack UA. Подробности запуска — data\\launcher.log."); BeginStop(); } }); } };
                }
                Log("Локальный сервер запущен; PID=" + ownedProcess.Id);
                Stopwatch wait = Stopwatch.StartNew();
                while (wait.ElapsedMilliseconds < 60000 && !stopping)
                {
                    if (ownedProcess.HasExited) throw new InvalidOperationException("Локальный сервер не смог запуститься. Проверьте доступ к папке данных и перезапустите приложение.");
                    IndexResult index = ReadRunningIndex(dataDirectory, 1000);
                    if (index.Ready)
                    {
                        if (index.ServerPid != ownedProcess.Id) throw new InvalidOperationException("Обнаружен другой экземпляр приложения. Запуск отменён без его остановки.");
                        if (ServerCompatibility(index.ApiVersion) != 0) throw new InvalidOperationException("Версия сервера не соответствует отдельному окну приложения.");
                        port = index.Port;
                        ownedToken = index.Token;
                        Ui(delegate { Ready(); }); return;
                    }
                    Thread.Sleep(150);
                }
                if (!stopping) throw new InvalidOperationException("Сервер не подтвердил готовность за 60 секунд. Перезапустите приложение и проверьте доступность папки данных.");
            }
            catch (Exception exception)
            {
                Log("Ошибка запуска: " + exception.GetType().Name);
                string message = exception is InvalidOperationException ? exception.Message : "Не удалось запустить локальный сервер. Распакуйте весь архив в доступную для записи папку.";
                Ui(delegate { if (!stopping) ShowError(message); BeginStop(); });
            }
        }

        private void Ready()
        {
            if (stopping) return;
            tray.Text = "DomTrack UA — работает"; openItem.Enabled = true;
            Log("Приложение готово: " + BaseUrl);
            OpenApplicationWindow();
        }

        private void OpenApplicationWindow()
        {
            if (stopping || appWindow == null || appWindow.IsDisposed) return;
            if (!appWindow.Visible) appWindow.Show();
            if (appWindow.WindowState == FormWindowState.Minimized) appWindow.WindowState = FormWindowState.Normal;
            appWindow.BringToFront(); appWindow.Activate();
            if (port > 0 && appWindow.AppPort != port) appWindow.OpenApplication(port);
        }

        internal static void UpgradeOlderServer(string directory, IndexResult expected)
        {
            UpgradeOlderServerWithTimeout(directory, expected, 60000);
        }

        internal static void UpgradeOlderServerWithTimeout(string directory, IndexResult expected, int timeoutMilliseconds)
        {
            IndexResult current = ReadRunningIndex(directory, 2000);
            if (!current.Ready)
            {
                try { using (Process active = Process.GetProcessById(expected.ServerPid)) { if (!active.HasExited) throw new InvalidOperationException("Работающий экземпляр не подтвердил своё состояние; обновление отменено без запуска второго сервера."); } }
                catch (ArgumentException) { }
                return;
            }
            if (current.ServerPid != expected.ServerPid || current.InstanceId != expected.InstanceId || current.DataId != expected.DataId || current.Port != expected.Port || ServerCompatibility(current.ApiVersion) >= 0) throw new InvalidOperationException("Работающий экземпляр изменился; обновление отменено.");
            Process previous;
            try { previous = Process.GetProcessById(current.ServerPid); }
            catch (ArgumentException) { return; }
            using (previous)
            {
                if (previous.HasExited) return;
                byte[] data = Encoding.UTF8.GetBytes("{\"serverPid\":" + current.ServerPid + "}");
                HttpWebRequest request = Request(current.Port, "/api/shutdown", "POST", 4000, current.Token);
                request.ContentType = "application/json"; request.ContentLength = data.Length;
                using (Stream stream = request.GetRequestStream()) stream.Write(data, 0, data.Length);
                using (HttpWebResponse response = (HttpWebResponse)request.GetResponse()) { if (response.StatusCode != HttpStatusCode.OK) throw new InvalidOperationException("Старая версия не подтвердила остановку."); ReadBody(response); }
                // HTTP timeout or removal of endpoint metadata does not prove the old database user exited.
                if (previous.WaitForExit(Math.Max(1, timeoutMilliseconds))) return;
            }
            throw new InvalidOperationException("Старая версия ещё завершает работу. Закройте её через значок возле часов, затем повторите запуск.");
        }

        private static bool RecordedProcessIsAlive(string directory)
        {
            try
            {
                string file = Path.Combine(directory, "instance.json");
                if (!File.Exists(file) || new FileInfo(file).Length > 4096) return false;
                string body = File.ReadAllText(file, Encoding.UTF8);
                int savedPort = NumberField(body, "port"), savedPid = NumberField(body, "serverPid");
                if (savedPort < 1 || savedPort > 65535 || savedPid < 1 || HexField(body, "instanceId", 32) == null || HexField(body, "dataId", 64) != DataDirectoryId(directory) || !Regex.IsMatch(body, "\"appId\"\\s*:\\s*\"domtrack-ua\"")) return false;
                using (Process active = Process.GetProcessById(savedPid)) return !active.HasExited;
            }
            catch (ArgumentException) { return false; }
            catch (IOException) { return false; }
            catch (UnauthorizedAccessException) { return true; }
        }

        private void BeginStop()
        {
            if (stopping) return;
            stopping = true; stopItem.Enabled = false; openItem.Enabled = false;
            tray.Text = "DomTrack UA — останавливается";
            if (appWindow != null && !appWindow.IsDisposed) appWindow.SetStatus("Завершаем работу DomTrack UA…");
            ThreadPool.QueueUserWorkItem(delegate { StopOwned(); Ui(delegate { if (appWindow != null && !appWindow.IsDisposed) appWindow.Close(); ExitThread(); }); });
        }

        private void StopOwned()
        {
            lock (stopLock)
            {
                if (stopped) return; stopped = true;
                if (ownedProcess == null) return;
                try
                {
                    if (ownedProcess.HasExited) return;
                    if (ownedToken != null)
                    {
                        try
                        {
                            IndexResult index = ReadRunningIndex(dataDirectory, 1200);
                            if (index.Ready && index.Port == port && index.Token == ownedToken && index.ServerPid == ownedProcess.Id)
                            {
                                byte[] data = Encoding.UTF8.GetBytes("{\"serverPid\":" + ownedProcess.Id + "}");
                                HttpWebRequest request = Request(port, "/api/shutdown", "POST", 2000, ownedToken);
                                request.ContentType = "application/json"; request.ContentLength = data.Length;
                                using (Stream stream = request.GetRequestStream()) stream.Write(data, 0, data.Length);
                                using (HttpWebResponse response = (HttpWebResponse)request.GetResponse()) { }
                                Log("Собственному серверу отправлен запрос корректной остановки.");
                            }
                        }
                        catch { Log("Корректная остановка не подтверждена; ожидается собственный процесс."); }
                    }
                    if (!ownedProcess.WaitForExit(15000))
                    {
                        ownedProcess.Kill(); ownedProcess.WaitForExit(2000);
                        Log("Завершён только собственный процесс сервера.");
                    }
                }
                catch { Log("Процесс собственного сервера уже недоступен."); }
            }
        }

        private void Ui(Action action)
        {
            if (disposed) return;
            try { dispatcher.BeginInvoke((MethodInvoker)delegate { if (!disposed) action(); }); } catch (InvalidOperationException) { }
        }
        private void ShowError(string message) { MessageBox.Show(message, "DomTrack UA", MessageBoxButtons.OK, MessageBoxIcon.Error); }
        private void Log(string message)
        {
            lock (logLock)
            {
                try
                {
                    Directory.CreateDirectory(Path.GetDirectoryName(logPath));
                    if (File.Exists(logPath) && new FileInfo(logPath).Length > 256 * 1024) File.WriteAllText(logPath, "Журнал сокращён.\r\n", new UTF8Encoding(false));
                    File.AppendAllText(logPath, DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") + " " + message + "\r\n", new UTF8Encoding(false));
                }
                catch { }
            }
        }
        protected override void Dispose(bool disposing)
        {
            if (!disposed)
            {
                stopping = true; StopOwned(); disposed = true;
                if (disposing) { activationRegistration.Unregister(null); if (appWindow != null) appWindow.Dispose(); tray.Visible = false; tray.Dispose(); applicationIcon.Dispose(); dispatcher.Dispose(); if (ownedProcess != null) ownedProcess.Dispose(); }
                Log("Панель закрыта.");
            }
            base.Dispose(disposing);
        }
    }
}
