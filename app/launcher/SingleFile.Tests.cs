using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Text;

namespace DomTrackSingleFile
{
    internal static class PackageTests
    {
        // Read embedded files as PE data only. This never loads executable code.
        private sealed class DataOnlyResources : Assembly
        {
            private readonly Dictionary<string, byte[]> resources;
            internal DataOnlyResources(string directory)
            {
                resources = new Dictionary<string, byte[]>(StringComparer.Ordinal);
                foreach (string name in new string[] { "DomTrack.Payload", "DomTrack.Manifest" })
                    resources.Add(name, File.ReadAllBytes(Path.Combine(directory, name)));
            }
            public override Stream GetManifestResourceStream(string name)
            {
                byte[] bytes;
                return resources.TryGetValue(name, out bytes) ? new MemoryStream(bytes, false) : null;
            }
        }
        private static int passed;
        private static void Check(bool condition, string message) { if (!condition) throw new Exception(message); }
        private static void Test(string name, Action action) { action(); passed++; Console.WriteLine("PASS " + name); }
        private static void Reject(Action action) { bool failed = false; try { action(); } catch (InvalidDataException) { failed = true; } Check(failed, "Invalid package was accepted"); }
        private static MemoryStream Zip(string[] names, string content)
        {
            MemoryStream result = new MemoryStream();
            using (ZipArchive archive = new ZipArchive(result, ZipArchiveMode.Create, true))
            foreach (string name in names) using (Stream output = archive.CreateEntry(name).Open()) { byte[] data = Encoding.UTF8.GetBytes(content); output.Write(data, 0, data.Length); }
            result.Position = 0; return result;
        }
        private static string Digest(string content) { using (MemoryStream stream = new MemoryStream(Encoding.UTF8.GetBytes(content))) return PackageManager.Hash(stream); }
        private static Dictionary<string, PackageFile> Files(string name, string content)
        {
            Dictionary<string, PackageFile> files = new Dictionary<string, PackageFile>(StringComparer.OrdinalIgnoreCase);
            files.Add(name, new PackageFile { Name = name, Length = Encoding.UTF8.GetByteCount(content), Hash = Digest(content) }); return files;
        }
        private static string Fresh(string root, string name) { string path = Path.Combine(root, name); Directory.CreateDirectory(path); return path; }
        private static int Main(string[] args)
        {
            AppContext.SetSwitch("Switch.System.IO.UseLegacyPathHandling", false);
            AppContext.SetSwitch("Switch.System.IO.BlockLongPaths", false);
            try
            {
                if (args.Length != 2) throw new Exception("Expected data-only PE resources and workspace test directory");
                string resourceDirectory = Path.GetFullPath(args[0]);
                string root = Path.GetFullPath(args[1]); Directory.CreateDirectory(root);
                Assembly actual = new DataOnlyResources(resourceDirectory);
                Dictionary<string, PackageFile> actualFiles = null;
                Test("embedded payload and manifest match their SHA-256", delegate {
                    using (Stream payload = actual.GetManifestResourceStream("DomTrack.Payload")) Check(payload != null && PackageManager.Hash(payload) == BuildInfo.PayloadHash, "Payload mismatch");
                    using (Stream manifest = actual.GetManifestResourceStream("DomTrack.Manifest")) actualFiles = PackageManager.ReadManifest(manifest, BuildInfo.ManifestHash);
                    foreach (string required in new string[] { "DomTrack-UA.exe", "DomTrack-UA.exe.config", "runtime/node.exe", "runtime/LICENSE.node.txt", "runtime/webview2/Microsoft.Web.WebView2.Core.dll", "runtime/webview2/Microsoft.Web.WebView2.WinForms.dll", "runtime/webview2/WebView2Loader.dll", "runtime/webview2/LICENSE.txt", "runtime/webview2/NOTICE.txt", "server.cjs", "lib/key-vault.cjs", "lib/key-vault.ps1", "public/index.html", "public/app.ico", "public/app-icon.svg", "lib/catalog.cjs", "lib/source-registry.cjs", "lib/multi-source.cjs", "lib/currency.cjs", "README.md" }) Check(actualFiles.ContainsKey(required), "Missing required file");
                    foreach (string name in actualFiles.Keys) Check(!name.StartsWith("data/", StringComparison.OrdinalIgnoreCase) && !name.EndsWith(".sqlite") && !name.EndsWith(".enc.json") && !name.EndsWith(".log") && name != ".env", "Personal runtime data included");
                });
                Test("absolute, traversal, ADS and Windows special paths are rejected", delegate {
                    foreach (string bad in new string[] { "../escape", "a/../escape", "C:/escape", "/escape", "\\\\server\\share", "a:stream", "a//b", "a/./b", "CON", "aux.txt", "a/NUL", "a/COM1.txt", "a/name.", "a/name ", "a/na*me", "a\tbad" }) Reject(delegate { PackageManager.RelativeName(bad); });
                    Check(PackageManager.RelativeName("каталог с пробелом/данные.txt") == "каталог с пробелом/данные.txt", "Unicode path rejected");
                });
                Test("manifest corruption and duplicate case-insensitive names are rejected", delegate {
                    using (MemoryStream input = new MemoryStream(Encoding.UTF8.GetBytes("corrupt"))) Reject(delegate { PackageManager.ReadManifest(input, new string('0', 64)); });
                    string text = Digest("x") + "\t1\tnote.txt\n" + Digest("x") + "\t1\tNOTE.TXT\n";
                    using (MemoryStream input = new MemoryStream(Encoding.UTF8.GetBytes(text))) Reject(delegate { PackageManager.ReadManifest(input, Digest(text)); });
                });
                Test("ZIP traversal cannot create a file outside extraction", delegate {
                    string target = Fresh(root, "traversal");
                    using (MemoryStream zip = Zip(new string[] { "../escape.txt" }, "x")) Reject(delegate { PackageManager.Extract(zip, target, Files("note.txt", "x"), null); });
                    Check(!File.Exists(Path.Combine(root, "escape.txt")), "Escaped extraction root");
                });
                Test("duplicate ZIP names are rejected on Windows", delegate {
                    using (MemoryStream zip = Zip(new string[] { "note.txt", "NOTE.TXT" }, "x")) Reject(delegate { PackageManager.Extract(zip, Fresh(root, "duplicates"), Files("note.txt", "x"), null); });
                });
                Test("missing and unexpected files are rejected", delegate {
                    Dictionary<string, PackageFile> files = Files("note.txt", "x"); files.Add("required.txt", new PackageFile { Name = "required.txt", Length = 1, Hash = Digest("x") });
                    using (MemoryStream zip = Zip(new string[] { "note.txt" }, "x")) Reject(delegate { PackageManager.Extract(zip, Fresh(root, "missing"), files, null); });
                    using (MemoryStream zip = Zip(new string[] { "unexpected.txt" }, "x")) Reject(delegate { PackageManager.Extract(zip, Fresh(root, "extra"), Files("note.txt", "x"), null); });
                });
                Test("wrong declared length and changed file contents are rejected", delegate {
                    using (MemoryStream zip = Zip(new string[] { "note.txt" }, "long")) Reject(delegate { PackageManager.Extract(zip, Fresh(root, "length"), Files("note.txt", "x"), null); });
                    using (MemoryStream zip = Zip(new string[] { "note.txt" }, "y")) Reject(delegate { PackageManager.Extract(zip, Fresh(root, "digest"), Files("note.txt", "x"), null); });
                });
                string home = Fresh(root, "папка пользователя с пробелом");
                string versions = Path.Combine(home, "app"), dataDirectory = Fresh(home, "data");
                string sentinel = Path.Combine(dataDirectory, "test-observations.txt"); File.WriteAllText(sentinel, "keep-user-data");
                string destination = Path.Combine(versions, BuildInfo.PayloadHash.Substring(0, 24));
                Test("the real executable extracts a complete app from a Unicode path", delegate {
                    PackageManager.Prepare(actual, versions, destination, null);
                    Check(PackageManager.Verify(destination, actualFiles, null), "Extracted app differs");
                    Check(File.ReadAllText(sentinel) == "keep-user-data", "User data changed");
                });
                Test("repeat launch reuses files and keeps user data", delegate {
                    string file = Path.Combine(destination, "public", "index.html"); DateTime stamp = File.GetLastWriteTimeUtc(file);
                    PackageManager.Prepare(actual, versions, destination, null);
                    Check(stamp == File.GetLastWriteTimeUtc(file), "Cache unnecessarily rewritten");
                    Check(File.ReadAllText(sentinel) == "keep-user-data", "User data changed");
                });
                Test("cache repair preserves user data and recovers the complete package", delegate {
                    File.WriteAllText(Path.Combine(destination, "public", "index.html"), "damaged-test-cache");
                    PackageManager.Prepare(actual, versions, destination, null);
                    Check(PackageManager.Verify(destination, actualFiles, null), "Repair failed");
                    Check(File.ReadAllText(sentinel) == "keep-user-data", "Repair changed data");
                    Check(Directory.GetDirectories(versions, ".previous-*").Length == 1, "Damaged cache not retained");
                });
                Console.WriteLine("RESULT " + passed + " tests passed; no GUI or browser launched.");
                Console.WriteLine("EXTRACTED " + destination);
                return 0;
            }
            catch (Exception error) { Console.Error.WriteLine("FAIL " + error.ToString()); return 1; }
        }
    }
}
