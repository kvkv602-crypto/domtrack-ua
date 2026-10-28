using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Windows.Forms;

[assembly: AssemblyTitle("DomTrack UA — один файл")]
[assembly: AssemblyDescription("Автономный запуск DomTrack UA со встроенным runtime")]
[assembly: AssemblyProduct("DomTrack UA")]
[assembly: AssemblyVersion("1.2.0.0")]
[assembly: AssemblyFileVersion("1.2.0.0")]
[assembly: System.Runtime.Versioning.TargetFramework(".NETFramework,Version=v4.6.2", FrameworkDisplayName = ".NET Framework 4.6.2")]

namespace DomTrackSingleFile
{
    internal static class Program
    {
        [STAThread]
        private static void Main()
        {
            AppContext.SetSwitch("Switch.System.IO.UseLegacyPathHandling", false);
            AppContext.SetSwitch("Switch.System.IO.BlockLongPaths", false);
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            using (PreparingWindow window = new PreparingWindow())
            {
                try
                {
                    string local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
                    if (String.IsNullOrEmpty(local)) throw new IOException();
                    string home = Path.Combine(local, "DomTrackUA");
                    string versions = Path.Combine(home, "app");
                    string data = Path.Combine(home, "data");
                    string destination = Path.Combine(versions, BuildInfo.PayloadHash.Substring(0, 24));
                    window.Show(); window.Refresh();
                    using (Mutex installation = new Mutex(false, @"Local\DomTrack-UA-Package-" + BuildInfo.PayloadHash.Substring(0, 24)))
                    {
                        bool acquired = false;
                        try
                        {
                            try { acquired = installation.WaitOne(60000); }
                            catch (AbandonedMutexException) { acquired = true; }
                            if (!acquired) throw new IOException();
                            PackageManager.Prepare(Assembly.GetExecutingAssembly(), versions, destination, window.Progress);
                        }
                        finally { if (acquired) installation.ReleaseMutex(); }
                    }
                    Directory.CreateDirectory(PackageManager.IoPath(data));
                    ProcessStartInfo info = new ProcessStartInfo(Path.Combine(destination, "DomTrack-UA.exe"));
                    info.UseShellExecute = false; info.CreateNoWindow = true; info.WindowStyle = ProcessWindowStyle.Normal;
                    info.WorkingDirectory = destination;
                    info.EnvironmentVariables["DOMTRACK_DATA_DIR"] = data;
                    info.EnvironmentVariables.Remove("NODE_OPTIONS"); info.EnvironmentVariables.Remove("NODE_PATH");
                    window.Hide();
                    using (Process child = Process.Start(info)) { if (child == null) throw new IOException(); }
                }
                catch
                {
                    window.Hide();
                    MessageBox.Show("Не удалось подготовить или запустить DomTrack UA. Проверьте свободное место и доступ к папке %LOCALAPPDATA%\\DomTrackUA, затем запустите файл ещё раз. Ваши данные находятся в подпапке data и при подготовке программы не заменяются.", "DomTrack UA", MessageBoxButtons.OK, MessageBoxIcon.Error);
                }
            }
        }
    }

    internal sealed class PreparingWindow : Form
    {
        private readonly Label message;
        private readonly ProgressBar progress;
        internal PreparingWindow()
        {
            Text = "DomTrack UA"; ClientSize = new Size(430, 110);
            try { Icon = System.Drawing.Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }
            FormBorderStyle = FormBorderStyle.FixedDialog; StartPosition = FormStartPosition.CenterScreen;
            ControlBox = false; MaximizeBox = false; MinimizeBox = false;
            message = new Label(); message.SetBounds(22, 18, 390, 38);
            message.Text = "Подготовка DomTrack UA…\r\nСкачивание дополнительных файлов не требуется.";
            progress = new ProgressBar(); progress.SetBounds(22, 68, 386, 18);
            Controls.Add(message); Controls.Add(progress);
        }
        internal void Progress(int percent)
        {
            progress.Value = Math.Max(0, Math.Min(100, percent));
            if (percent == 100) message.Text = "Открываем DomTrack UA…";
            Application.DoEvents();
        }
    }

    internal sealed class PackageFile
    {
        internal string Name;
        internal long Length;
        internal string Hash;
    }

    internal static class PackageManager
    {
        internal const long MaximumBytes = 512L * 1024 * 1024;
        internal static string Hash(Stream stream)
        {
            using (SHA256 sha = SHA256.Create()) return BitConverter.ToString(sha.ComputeHash(stream)).Replace("-", "").ToLowerInvariant();
        }
        internal static string IoPath(string value)
        {
            if (value.StartsWith(@"\\?\", StringComparison.Ordinal)) return value;
            string full = Path.GetFullPath(value);
            return full.StartsWith(@"\\", StringComparison.Ordinal) ? @"\\?\UNC\" + full.Substring(2) : @"\\?\" + full;
        }
        internal static string FileHash(string file) { using (FileStream input = File.OpenRead(IoPath(file))) return Hash(input); }
        internal static string RelativeName(string name)
        {
            if (String.IsNullOrEmpty(name) || name.Length > 500 || name.IndexOf(':') >= 0 || name.StartsWith("/") || name.StartsWith("\\")) throw new InvalidDataException();
            string normalized = name.Replace('\\', '/');
            string[] parts = normalized.Split('/');
            foreach (string part in parts)
            {
                if (String.IsNullOrEmpty(part) || part == "." || part == ".." || part.EndsWith(".") || part.EndsWith(" ")) throw new InvalidDataException();
                foreach (char c in part) if (c < 32 || "<>\"|?*".IndexOf(c) >= 0) throw new InvalidDataException();
                string stem = part.Split('.')[0].ToUpperInvariant();
                if (stem == "CON" || stem == "PRN" || stem == "AUX" || stem == "NUL" || (stem.Length == 4 && (stem.StartsWith("COM") || stem.StartsWith("LPT")) && stem[3] >= '1' && stem[3] <= '9')) throw new InvalidDataException();
            }
            return normalized;
        }
        internal static string ChildPath(string root, string relative)
        {
            string fullRoot = Path.GetFullPath(root).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
            string full = Path.GetFullPath(Path.Combine(fullRoot, RelativeName(relative).Replace('/', Path.DirectorySeparatorChar)));
            if (!full.StartsWith(fullRoot, StringComparison.OrdinalIgnoreCase)) throw new InvalidDataException();
            return full;
        }
        internal static void NoReparsePoints(string root, string file)
        {
            string stop = Path.GetFullPath(root).TrimEnd(Path.DirectorySeparatorChar);
            string current = Path.GetFullPath(file).TrimEnd(Path.DirectorySeparatorChar);
            if (current != stop && !current.StartsWith(stop + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)) throw new InvalidDataException();
            while (current.Length >= stop.Length)
            {
                string access = IoPath(current);
                if ((File.Exists(access) || Directory.Exists(access)) && (File.GetAttributes(access) & FileAttributes.ReparsePoint) != 0) throw new InvalidDataException();
                if (String.Equals(current, stop, StringComparison.OrdinalIgnoreCase)) break;
                current = Path.GetDirectoryName(current);
            }
        }
        internal static Dictionary<string, PackageFile> ReadManifest(Stream stream, string expectedHash)
        {
            using (MemoryStream copy = new MemoryStream())
            {
                byte[] buffer = new byte[8192]; int count;
                while ((count = stream.Read(buffer, 0, buffer.Length)) > 0) { copy.Write(buffer, 0, count); if (copy.Length > 1024 * 1024) throw new InvalidDataException(); }
                copy.Position = 0; if (Hash(copy) != expectedHash) throw new InvalidDataException(); copy.Position = 0;
                Dictionary<string, PackageFile> result = new Dictionary<string, PackageFile>(StringComparer.OrdinalIgnoreCase);
                long total = 0;
                using (StreamReader reader = new StreamReader(copy, new UTF8Encoding(false, true)))
                {
                    string line;
                    while ((line = reader.ReadLine()) != null)
                    {
                        if (line.Length == 0) continue;
                        string[] pieces = line.Split('\t'); long length;
                        if (pieces.Length != 3 || pieces[0].Length != 64 || !Int64.TryParse(pieces[1], out length) || length < 0 || length > MaximumBytes) throw new InvalidDataException();
                        foreach (char c in pieces[0]) if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f')) throw new InvalidDataException();
                        string name = RelativeName(pieces[2]);
                        if (result.ContainsKey(name) || result.Count >= 2000) throw new InvalidDataException();
                        total = checked(total + length); if (total > MaximumBytes) throw new InvalidDataException();
                        result.Add(name, new PackageFile { Name = name, Length = length, Hash = pieces[0] });
                    }
                }
                if (result.Count == 0) throw new InvalidDataException();
                return result;
            }
        }
        internal static bool Verify(string directory, Dictionary<string, PackageFile> files, Action<int> progress)
        {
            try
            {
                if (!Directory.Exists(IoPath(directory))) return false;
                int done = 0;
                foreach (PackageFile file in files.Values)
                {
                    string target = ChildPath(directory, file.Name); NoReparsePoints(directory, target);
                    if (!File.Exists(IoPath(target)) || new FileInfo(IoPath(target)).Length != file.Length || FileHash(target) != file.Hash) return false;
                    if (progress != null) progress((++done * 30) / files.Count);
                }
                return true;
            }
            catch { return false; }
        }
        internal static void Extract(Stream package, string destination, Dictionary<string, PackageFile> files, Action<int> progress)
        {
            Dictionary<string, bool> seen = new Dictionary<string, bool>(StringComparer.OrdinalIgnoreCase);
            using (ZipArchive archive = new ZipArchive(package, ZipArchiveMode.Read, true))
            {
                foreach (ZipArchiveEntry entry in archive.Entries)
                {
                    if (entry.FullName.EndsWith("/") || entry.FullName.EndsWith("\\")) { RelativeName(entry.FullName.TrimEnd('/', '\\')); continue; }
                    string name = RelativeName(entry.FullName); PackageFile file;
                    if (seen.ContainsKey(name) || !files.TryGetValue(name, out file) || entry.Length != file.Length) throw new InvalidDataException();
                    seen.Add(name, true);
                    string target = ChildPath(destination, name); NoReparsePoints(destination, target);
                    Directory.CreateDirectory(IoPath(Path.GetDirectoryName(target)));
                    using (Stream input = entry.Open())
                    using (FileStream output = new FileStream(IoPath(target), FileMode.CreateNew, FileAccess.Write, FileShare.None))
                    {
                        byte[] buffer = new byte[65536]; long total = 0; int count;
                        while ((count = input.Read(buffer, 0, buffer.Length)) > 0) { total = checked(total + count); if (total > file.Length) throw new InvalidDataException(); output.Write(buffer, 0, count); }
                        if (total != file.Length) throw new InvalidDataException();
                    }
                    if (FileHash(target) != file.Hash) throw new InvalidDataException();
                    if (progress != null) progress(30 + (seen.Count * 65) / files.Count);
                }
            }
            if (seen.Count != files.Count) throw new InvalidDataException();
        }
        internal static string Prepare(Assembly assembly, string versions, string destination, Action<int> progress)
        {
            Dictionary<string, PackageFile> files;
            using (Stream manifest = assembly.GetManifestResourceStream("DomTrack.Manifest"))
            { if (manifest == null) throw new InvalidDataException(); files = ReadManifest(manifest, BuildInfo.ManifestHash); }
            NoReparsePoints(Path.GetDirectoryName(Path.GetFullPath(versions)), versions);
            string expected = ChildPath(versions, BuildInfo.PayloadHash.Substring(0, 24));
            if (!String.Equals(expected, Path.GetFullPath(destination), StringComparison.OrdinalIgnoreCase)) throw new InvalidDataException();
            if (Verify(destination, files, progress)) { if (progress != null) progress(100); return destination; }
            Directory.CreateDirectory(IoPath(versions));
            string staging = ChildPath(versions, ".preparing-" + Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(IoPath(staging));
            try
            {
                using (Stream package = assembly.GetManifestResourceStream("DomTrack.Payload"))
                { if (package == null || Hash(package) != BuildInfo.PayloadHash) throw new InvalidDataException(); }
                using (Stream package = assembly.GetManifestResourceStream("DomTrack.Payload")) Extract(package, staging, files, progress);
                if (Directory.Exists(IoPath(destination)))
                {
                    NoReparsePoints(versions, destination);
                    // Keep a damaged installation for recovery; the separate user data is never moved.
                    string backup = ChildPath(versions, ".previous-" + Guid.NewGuid().ToString("N"));
                    Directory.Move(IoPath(destination), IoPath(backup));
                }
                Directory.Move(IoPath(staging), IoPath(destination));
                if (progress != null) progress(100);
                return destination;
            }
            finally
            {
                if (Directory.Exists(IoPath(staging)))
                {
                    NoReparsePoints(versions, staging);
                    if (!Path.GetFullPath(staging).StartsWith(Path.GetFullPath(versions).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)) throw new InvalidDataException();
                    Directory.Delete(IoPath(staging), true);
                }
            }
        }
    }
}
