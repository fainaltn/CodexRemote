import type { Metadata, Viewport } from "next";
import { JetBrains_Mono, Manrope } from "next/font/google";
import "./globals.css";
import { AuthProvider } from "@/lib/auth";
import { AppShellClient } from "./app-shell-client";

const THEME_STORAGE_KEY = "codexremote_theme_pref";
const manrope = Manrope({
  subsets: ["latin"],
  variable: "--font-sans",
  display: "swap",
});
const jetbrainsMono = JetBrains_Mono({
  subsets: ["latin"],
  variable: "--font-mono",
  display: "swap",
});

export const metadata: Metadata = {
  title: "CodexRemote",
  description: "Remote control for Codex CLI",
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  maximumScale: 1,
  userScalable: false,
  viewportFit: "cover",
  themeColor: "#07111f",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="zh-CN" suppressHydrationWarning>
      <head>
        <script
          dangerouslySetInnerHTML={{
            __html: `
              (function() {
                var pref = localStorage.getItem('${THEME_STORAGE_KEY}') || 'auto';
                var hour = new Date().getHours();
                var resolved = pref === 'dark'
                  ? 'dark'
                  : pref === 'light'
                    ? 'light'
                    : (hour < 7 || hour >= 19 ? 'dark' : 'light');
                document.documentElement.dataset.themePref = pref;
                document.documentElement.dataset.theme = resolved;
              })();
            `,
          }}
        />
      </head>
      <body className={`${manrope.variable} ${jetbrainsMono.variable}`}>
        <AuthProvider>
          <AppShellClient>{children}</AppShellClient>
        </AuthProvider>
        <button
          className="theme-toggle"
          type="button"
          aria-label="切换主题"
          aria-pressed="false"
          data-theme-toggle="true"
          title="切换主题"
        >
          <span className="theme-toggle-icon theme-toggle-icon-light" aria-hidden="true">
            ☀
          </span>
          <span className="theme-toggle-icon theme-toggle-icon-dark" aria-hidden="true">
            ☾
          </span>
          <span className="theme-toggle-label">主题</span>
        </button>
        <script
          dangerouslySetInnerHTML={{
            __html: `
              (function() {
                var storageKey = '${THEME_STORAGE_KEY}';
                var root = document.documentElement;
                var button = document.querySelector('[data-theme-toggle="true"]');

                function getResolvedTheme(pref) {
                  if (pref === 'dark' || pref === 'light') {
                    return pref;
                  }
                  var hour = new Date().getHours();
                  return hour < 7 || hour >= 19 ? 'dark' : 'light';
                }

                function syncButton(theme) {
                  if (!button) return;
                  button.setAttribute('aria-pressed', theme === 'dark' ? 'true' : 'false');
                  button.setAttribute('aria-label', theme === 'dark' ? '切换到浅色主题' : '切换到深色主题');
                  button.setAttribute('title', theme === 'dark' ? '当前深色，点击切换到浅色主题' : '当前浅色，点击切换到深色主题');
                  button.dataset.theme = theme;
                }

                function applyTheme(pref, persist) {
                  var theme = getResolvedTheme(pref);
                  root.dataset.themePref = pref;
                  root.dataset.theme = theme;
                  syncButton(theme);
                  if (persist) {
                    try {
                      localStorage.setItem(storageKey, pref);
                    } catch (_) {
                      // Ignore storage failures and keep the theme in-memory.
                    }
                  }
                }

                var stored = null;
                try {
                  stored = localStorage.getItem(storageKey);
                } catch (_) {
                  stored = null;
                }

                applyTheme(stored === 'dark' || stored === 'light' ? stored : 'auto', false);

                document.addEventListener('click', function(event) {
                  var target = event.target instanceof Element
                    ? event.target.closest('[data-theme-toggle="true"]')
                    : null;
                  if (!target) return;
                  event.preventDefault();
                  var nextTheme = root.dataset.theme === 'dark' ? 'light' : 'dark';
                  applyTheme(nextTheme, true);
                });
              })();
            `,
          }}
        />
      </body>
    </html>
  );
}
