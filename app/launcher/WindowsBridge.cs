using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;

namespace DomTrackLauncher
{
    // Invoked by the packaged launcher before normal GUI initialization. The
    // protocol accepts data only; there is no script, command or file operation.
    internal static class WindowsBridge
    {
        private const int MaximumInputBytes = 1024 * 1024;
        private const int MaximumOutputBytes = 1024 * 1024;
        private static readonly UTF8Encoding Utf8 = new UTF8Encoding(false, true);

        internal static bool TryRun(string[] args)
        {
            if (args == null || args.Length == 0 || args[0] != "--windows-helper") return false;
            string kind = args.Length == 2 ? args[1] : null;
            try
            {
                if (kind != "vault" && kind != "notify") throw new InvalidDataException();
                Dictionary<string, object> request = ReadRequest();
                if (kind == "vault") WriteJson(new Dictionary<string, object> { { "ok", true }, { "value", RunVault(request) } });
                else RunNotification(request);
                Environment.ExitCode = 0;
            }
            catch
            {
                Environment.ExitCode = 1;
                try { WriteJson(new Dictionary<string, object> { { "ok", false }, { "code", kind == "notify" ? "WINDOWS_NOTIFY_UNAVAILABLE" : "WINDOWS_VAULT_FAILED" } }); }
                catch { /* A closed pipe is never a reason to open the GUI. */ }
            }
            return true;
        }

        private static Dictionary<string, object> ReadRequest()
        {
            Task<byte[]> read = Task.Run(delegate {
                using (Stream input = Console.OpenStandardInput())
                using (MemoryStream bytes = new MemoryStream())
                {
                    byte[] buffer = new byte[4096]; int count;
                    try
                    {
                        while ((count = input.Read(buffer, 0, buffer.Length)) > 0)
                        {
                            if (bytes.Length + count > MaximumInputBytes) throw new InvalidDataException();
                            bytes.Write(buffer, 0, count);
                        }
                        return bytes.ToArray();
                    }
                    finally { Array.Clear(buffer, 0, buffer.Length); Array.Clear(bytes.GetBuffer(), 0, (int)bytes.Length); }
                }
            });
            if (!read.Wait(10000)) throw new TimeoutException();
            byte[] raw = read.GetAwaiter().GetResult();
            try
            {
                string text = Utf8.GetString(raw).TrimStart('\uFEFF');
                JavaScriptSerializer json = new JavaScriptSerializer();
                json.MaxJsonLength = MaximumInputBytes; json.RecursionLimit = 8;
                Dictionary<string, object> request = json.DeserializeObject(text) as Dictionary<string, object>;
                if (request == null) throw new InvalidDataException();
                return request;
            }
            finally { Array.Clear(raw, 0, raw.Length); }
        }

        private static string Field(Dictionary<string, object> request, string name)
        {
            object value;
            if (!request.TryGetValue(name, out value) || !(value is string)) throw new InvalidDataException();
            return (string)value;
        }
        private static bool IsValidKey(string value)
        {
            return value.Length >= 1 && value.Length <= 500 && value.IndexOfAny(new char[] { '\r', '\n', '\0' }) < 0;
        }
        private static string RunVault(Dictionary<string, object> request)
        {
            if (request.Count != 2) throw new InvalidDataException();
            string operation = Field(request, "operation"), value = Field(request, "value");
            byte[] entropy = Utf8.GetBytes("DomTrackUA/key-vault/v1/CurrentUser");
            byte[] plain = null, cipher = null;
            try
            {
                if (operation == "protect")
                {
                    if (!IsValidKey(value)) throw new InvalidDataException();
                    plain = Utf8.GetBytes(value);
                    cipher = ProtectedData.Protect(plain, entropy, DataProtectionScope.CurrentUser);
                    return Convert.ToBase64String(cipher);
                }
                if (operation != "unprotect" || value.Length < 1 || value.Length > 64512) throw new InvalidDataException();
                cipher = Convert.FromBase64String(value);
                if (Convert.ToBase64String(cipher) != value) throw new InvalidDataException();
                plain = ProtectedData.Unprotect(cipher, entropy, DataProtectionScope.CurrentUser);
                string key = Utf8.GetString(plain);
                if (!IsValidKey(key)) throw new InvalidDataException();
                return key;
            }
            finally
            {
                if (plain != null) Array.Clear(plain, 0, plain.Length);
                if (cipher != null) Array.Clear(cipher, 0, cipher.Length);
                Array.Clear(entropy, 0, entropy.Length);
            }
        }

        private static void RunNotification(Dictionary<string, object> request)
        {
            if (request.Count != 2) throw new InvalidDataException();
            string title = Field(request, "title"), text = Field(request, "text");
            if (String.IsNullOrWhiteSpace(title) || title.Length > 120 || text.Length > 240 || title.IndexOf('\0') >= 0 || text.IndexOf('\0') >= 0 || !Environment.UserInteractive) throw new InvalidDataException();
            Application.EnableVisualStyles();
            using (NotifyIcon icon = new NotifyIcon())
            using (ApplicationContext context = new ApplicationContext())
            using (System.Windows.Forms.Timer lifetime = new System.Windows.Forms.Timer())
            {
                icon.Icon = SystemIcons.Information; icon.Text = "DomTrack UA"; icon.Visible = true;
                lifetime.Interval = 4500;
                lifetime.Tick += delegate { lifetime.Stop(); icon.Visible = false; context.ExitThread(); };
                try
                {
                    lifetime.Start();
                    icon.ShowBalloonTip(4000, title, text, ToolTipIcon.Info);
                    Application.Run(context);
                }
                finally { lifetime.Stop(); icon.Visible = false; }
            }
            // Windows may suppress balloons (Focus Assist, policy, user settings).
            // Successful dispatch is not confirmation that anything was displayed.
            WriteJson(new Dictionary<string, object> { { "ok", true }, { "delivery", "balloon-requested" } });
        }

        private static void WriteJson(Dictionary<string, object> value)
        {
            JavaScriptSerializer json = new JavaScriptSerializer(); json.MaxJsonLength = MaximumOutputBytes; json.RecursionLimit = 8;
            byte[] bytes = Utf8.GetBytes(json.Serialize(value));
            try
            {
                if (bytes.Length > MaximumOutputBytes) throw new InvalidDataException();
                using (Stream output = Console.OpenStandardOutput()) { output.Write(bytes, 0, bytes.Length); output.Flush(); }
            }
            finally { Array.Clear(bytes, 0, bytes.Length); }
        }
    }
}
