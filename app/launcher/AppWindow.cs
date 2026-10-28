using System;
using System.Diagnostics;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Text.RegularExpressions;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace DomTrackLauncher
{
    internal sealed class AppWindow : Form
    {
        private readonly string applicationRoot;
        private readonly string profileDirectory;
        private readonly WebView2 view;
        private readonly Panel overlay;
        private readonly Label message;
        private readonly Button retry;
        private readonly Icon windowIcon;
        private Uri origin;
        private bool initializing;
        private bool initialized;
        private readonly HashSet<ulong> hostCanceledNavigations = new HashSet<ulong>();
        internal int AppPort { get; private set; }

        internal AppWindow(string root, string dataDirectory, Icon icon)
        {
            applicationRoot = Path.GetFullPath(root);
            profileDirectory = Path.Combine(Path.GetDirectoryName(Path.GetFullPath(dataDirectory)), "webview");
            Text = "DomTrack UA"; StartPosition = FormStartPosition.CenterScreen;
            Rectangle workingArea = Screen.PrimaryScreen.WorkingArea;
            ClientSize = new Size(Math.Min(1280, Math.Max(360, workingArea.Width - 48)), Math.Min(850, Math.Max(300, workingArea.Height - 88)));
            MinimumSize = new Size(Math.Min(860, workingArea.Width - 16), Math.Min(620, workingArea.Height - 16));
            BackColor = Color.FromArgb(245, 247, 248); AutoScaleMode = AutoScaleMode.Dpi;
            windowIcon = (Icon)icon.Clone(); Icon = windowIcon;
            view = new WebView2(); view.Dock = DockStyle.Fill; view.Visible = false;
            overlay = new Panel(); overlay.Dock = DockStyle.Fill; overlay.BackColor = BackColor;
            message = new Label(); message.Text = "Подготавливаем DomTrack UA…";
            message.Dock = DockStyle.Fill; message.TextAlign = ContentAlignment.MiddleCenter;
            message.Font = new Font("Segoe UI", 12); message.Padding = new Padding(40);
            retry = new Button(); retry.Text = "Повторить"; retry.Dock = DockStyle.Bottom; retry.Height = 44; retry.Visible = false;
            retry.Click += delegate { OpenApplication(AppPort); };
            overlay.Controls.Add(message); overlay.Controls.Add(retry);
            Controls.Add(view); Controls.Add(overlay); overlay.BringToFront();
        }

        internal void SetStatus(string text)
        {
            if (IsDisposed) return;
            message.Text = text; retry.Visible = false; overlay.Visible = true; overlay.BringToFront();
        }

        internal async void OpenApplication(int port)
        {
            if (IsDisposed || port < 1 || port > 65535 || initializing) return;
            AppPort = port; origin = new Uri(LauncherContext.UrlFor(port));
            if (initialized) { view.CoreWebView2.Navigate(origin.AbsoluteUri); return; }
            initializing = true; SetStatus("Открываем приложение…");
            try
            {
                CoreWebView2Environment.SetLoaderDllFolderPath(Path.Combine(applicationRoot, "runtime", "webview2"));
                Directory.CreateDirectory(profileDirectory);
                CoreWebView2EnvironmentOptions options = new CoreWebView2EnvironmentOptions();
                options.Language = "ru-RU"; options.AllowSingleSignOnUsingOSPrimaryAccount = false;
                CoreWebView2Environment environment = await CoreWebView2Environment.CreateAsync(null, profileDirectory, options);
                if (IsDisposed) return;
                await view.EnsureCoreWebView2Async(environment);
                if (IsDisposed) return;
                CoreWebView2 core = view.CoreWebView2;
                core.Settings.IsStatusBarEnabled = false;
                core.Settings.IsPasswordAutosaveEnabled = false;
                core.Settings.IsGeneralAutofillEnabled = false;
                core.Settings.AreDevToolsEnabled = false;
                core.PermissionRequested += delegate(object sender, CoreWebView2PermissionRequestedEventArgs args) { args.State = CoreWebView2PermissionState.Deny; };
                core.NavigationStarting += NavigationStarting;
                core.NewWindowRequested += NewWindowRequested;
                core.DownloadStarting += DownloadStarting;
                core.NavigationCompleted += delegate(object sender, CoreWebView2NavigationCompletedEventArgs args) {
                    if (IsDisposed) return;
                    if (hostCanceledNavigations.Remove(args.NavigationId)) return;
                    if (args.IsSuccess) { overlay.Visible = false; view.Visible = true; view.BringToFront(); }
                    else { SetStatus("Не удалось загрузить локальное приложение. Проверьте, что сервер работает, и нажмите «Повторить»."); retry.Visible = true; }
                };
                core.ProcessFailed += delegate { if (!IsDisposed) { SetStatus("Встроенное окно завершило работу. Закройте и снова запустите DomTrack UA; база и настройки сохранены."); } };
                initialized = true; core.Navigate(origin.AbsoluteUri);
            }
            catch
            {
                if (!IsDisposed)
                {
                    SetStatus("Не удалось открыть встроенное окно приложения. Для него нужен Microsoft Edge WebView2 Runtime. Проверьте его наличие и перезапустите DomTrack UA. Обычный браузер автоматически не запускается.");
                    retry.Visible = true;
                }
            }
            finally { initializing = false; }
        }

        internal static bool IsApplicationUri(string value, Uri applicationOrigin)
        {
            Uri candidate;
            return applicationOrigin != null && Uri.TryCreate(value, UriKind.Absolute, out candidate) && candidate.Scheme == Uri.UriSchemeHttp && candidate.Host == "127.0.0.1" && candidate.Port == applicationOrigin.Port && String.IsNullOrEmpty(candidate.UserInfo);
        }

        internal static bool IsExternalWebUri(string value)
        {
            Uri candidate;
            return Uri.TryCreate(value, UriKind.Absolute, out candidate) && (candidate.Scheme == Uri.UriSchemeHttp || candidate.Scheme == Uri.UriSchemeHttps) && String.IsNullOrEmpty(candidate.UserInfo);
        }

        internal static bool IsExternalActionUri(string value)
        {
            if (IsExternalWebUri(value)) return true;
            if (String.IsNullOrEmpty(value) || value.Length > 500) return false;
            string decoded;
            try { decoded = Uri.UnescapeDataString(value); } catch { return false; }
            if (decoded.StartsWith("tel:", StringComparison.OrdinalIgnoreCase)) return Regex.IsMatch(decoded.Substring(4), @"^\+?[0-9]{5,20}$");
            if (decoded.StartsWith("mailto:", StringComparison.OrdinalIgnoreCase)) return Regex.IsMatch(decoded.Substring(7), @"^[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}$");
            return false;
        }

        private void NavigationStarting(object sender, CoreWebView2NavigationStartingEventArgs args)
        {
            if (IsApplicationUri(args.Uri, origin)) return;
            args.Cancel = true;
            if (hostCanceledNavigations.Count > 512) hostCanceledNavigations.Clear();
            hostCanceledNavigations.Add(args.NavigationId);
            if (args.IsUserInitiated) OpenExternal(args.Uri);
        }

        private void NewWindowRequested(object sender, CoreWebView2NewWindowRequestedEventArgs args)
        {
            args.Handled = true;
            if (!args.IsUserInitiated) return;
            if (IsApplicationUri(args.Uri, origin)) view.CoreWebView2.Navigate(args.Uri);
            else OpenExternal(args.Uri);
        }

        private void OpenExternal(string address)
        {
            if (!IsExternalActionUri(address)) return;
            BeginInvoke((Action)delegate {
                if (IsDisposed) return;
                try { Process.Start(new ProcessStartInfo(address) { UseShellExecute = true }); }
                catch { MessageBox.Show(this, "Не удалось открыть ссылку во внешнем браузере.", "DomTrack UA", MessageBoxButtons.OK, MessageBoxIcon.Information); }
            });
        }

        private void DownloadStarting(object sender, CoreWebView2DownloadStartingEventArgs args)
        {
            args.Cancel = true; args.Handled = true;
            string address = args.DownloadOperation.Uri;
            if (!IsApplicationUri(view.Source == null ? null : view.Source.AbsoluteUri, origin) || !(IsApplicationUri(address, origin) || address.StartsWith("blob:" + origin.GetLeftPart(UriPartial.Authority) + "/", StringComparison.Ordinal))) return;
            CoreWebView2Deferral deferral = args.GetDeferral();
            BeginInvoke((Action)delegate {
                try
                {
                    if (IsDisposed) return;
                    using (SaveFileDialog dialog = new SaveFileDialog())
                    {
                        dialog.Title = "Сохранить экспорт DomTrack UA";
                        dialog.Filter = "JSON (*.json)|*.json|Все файлы (*.*)|*.*";
                        dialog.FileName = "domtrack-export.json"; dialog.OverwritePrompt = true;
                        if (dialog.ShowDialog(this) == DialogResult.OK) { args.ResultFilePath = dialog.FileName; args.Cancel = false; }
                    }
                }
                finally { deferral.Complete(); }
            });
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing) { view.Dispose(); windowIcon.Dispose(); }
            base.Dispose(disposing);
        }
    }
}
