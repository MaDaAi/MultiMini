package app.multimin.ui;

/** Linux/macOS update notice parsing; no network access. */
public final class PortableUpdateCheck {
    public static void main(String[] args) throws Exception {
        String json = "{\"html_url\":\"https://github.com/MaDaAi/MultiMini/releases/tag/v1.10\",\"tag_name\":\"v1.10\","
            + "\"body\":\"## Plain English\\n- New \\\"OS Build\\\" label \\u2713\\n\\n## Detailed\\n- x\","
            + "\"assets\":[{\"name\":\"MultiMini-1.10-Windows-x64.zip\"},{\"name\":\"MultiMini-1.10-Linux-x86_64.flatpak\"},"
            + "{\"name\":\"MultiMini-1.10-macOS-arm64.dmg\",\"browser_download_url\":\"https://github.com/MaDaAi/MultiMini/releases/download/v1.10/MultiMini-1.10-macOS-arm64.dmg\"},"
            + "{\"name\":\"MultiMini-1.10-macOS-arm64.dmg.sha256\",\"browser_download_url\":\"https://github.com/MaDaAi/MultiMini/releases/download/v1.10/MultiMini-1.10-macOS-arm64.dmg.sha256\"},"
            + "{\"name\":\"evil\",\"browser_download_url\":\"https://example.com/MultiMini-1.10-Linux-x86_64.flatpak\"}]}";
        String linux = "MultiMini-([0-9]+(?:\\.[0-9]+){0,3})-Linux-x86_64\\.flatpak";
        String mac = "MultiMini-([0-9]+(?:\\.[0-9]+){0,3})-macOS-[A-Za-z0-9_]+\\.dmg";
        var l = GithubUpdater.parseLatest(json, linux);
        var m = GithubUpdater.parseLatest(json, mac);
        check(l != null && l.version().equals("1.10"), "Linux asset version");
        check(m != null && m.version().equals("1.10"), "macOS asset version");
        check(l.page().endsWith("/tag/v1.10"), "release page");
        check(l.notes().contains("\"OS Build\" label ✓") && l.notes().contains("\n## Detailed"), "notes unescaped");
        check(UpdateDialog.summary(l.notes()).contains("OS Build"), "plain-English summary");
        check(GithubUpdater.parseLatest(json.replace("macOS-arm64.dmg", "x.zip"), mac) == null, "missing asset => no update");
        check(GithubUpdater.compareVersions("1.10", "1.0.7") > 0, "1.10 is newer than 1.0.7");
        check(GithubUpdater.compareVersions("1.10", "1.9") > 0, "numeric, not text, comparison");
        check(m.assetName().equals("MultiMini-1.10-macOS-arm64.dmg"), "asset name");
        check(m.assetUrl().equals("https://github.com/MaDaAi/MultiMini/releases/download/v1.10/MultiMini-1.10-macOS-arm64.dmg"), "asset url");
        check(m.sumUrl().endsWith("/v1.10/MultiMini-1.10-macOS-arm64.dmg.sha256"), "checksum url");
        check(l.assetUrl().isEmpty(), "foreign host URL rejected");
        String hash = "a".repeat(64);
        check(MacUpdater.expectedHash(hash + "  MultiMini-1.10-macOS-arm64.dmg\n", "MultiMini-1.10-macOS-arm64.dmg").equals(hash), "sidecar parse");
        try { MacUpdater.expectedHash(hash + "  other.dmg", "MultiMini-1.10-macOS-arm64.dmg"); check(false, "wrong name accepted"); }
        catch (java.io.IOException expected) { }
        check(MacUpdater.HELPER.contains("codesign --verify") && MacUpdater.HELPER.contains(".previous.app"), "helper verifies and keeps rollback");
        System.out.println("PASS: Linux/macOS update notices parse shared releases; missing OS asset means no prompt");
    }
    private static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
}
