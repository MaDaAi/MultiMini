package app.multimin.ui;

import javax.swing.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * macOS in-app updater (untested preview). Mirrors the Windows flow:
 * confirm → download the .dmg and its .sha256 from this repository's release →
 * verify → save the workspace → quit → a helper script mounts the image, checks
 * the app's code signature, swaps it in with a rollback backup and relaunches.
 */
final class MacUpdater {
    private MacUpdater() {}

    static Path appBundle() throws IOException {
        try {
            Path jar = Path.of(MacUpdater.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            // MultiMini.app/Contents/app/MultiMini.jar
            Path bundle = jar.getParent().getParent().getParent();
            if (bundle.getFileName().toString().endsWith(".app") && Files.isRegularFile(bundle.resolve("Contents/Info.plist"))) return bundle;
        } catch (Exception ignored) { }
        throw new IOException("Automatic updates work in the installed MultiMini.app. Download the .dmg from GitHub Releases.");
    }

    static Path cache() throws IOException {
        Path path = Path.of(System.getProperty("user.home"), "Library", "Caches", "MultiMini", "updates");
        Files.createDirectories(path);
        return path;
    }

    /** Parse "<sha256>  <name>" and return the hash when the name matches exactly. */
    static String expectedHash(String sidecar, String name) throws IOException {
        var sum = Pattern.compile("(?i)^([0-9a-f]{64})\\s+\\*?" + Pattern.quote(name) + "$").matcher(sidecar.trim());
        if (!sum.matches()) throw new IOException("The release checksum file is invalid.");
        return sum.group(1).toLowerCase(Locale.ROOT);
    }

    static String digest(Path path) throws Exception {
        MessageDigest sum = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(path)) {
            byte[] bytes = new byte[1024 * 1024];
            for (int n; (n = in.read(bytes)) >= 0; ) sum.update(bytes, 0, n);
        }
        return HexFormat.of().formatHex(sum.digest());
    }

    static final String HELPER = """
        #!/bin/bash
        # MultiMini macOS updater helper. Args: app-pid dmg app-bundle
        set -u
        pid="$1"; dmg="$2"; app="$3"
        while kill -0 "$pid" 2>/dev/null; do sleep 0.5; done
        mnt=$(mktemp -d /tmp/multimini-update.XXXXXX)
        if ! hdiutil attach -nobrowse -readonly -mountpoint "$mnt" "$dmg"; then echo "mount failed"; open "$app"; exit 1; fi
        src="$mnt/MultiMini.app"
        fail() { echo "$1"; hdiutil detach "$mnt" -quiet; [ -d "$app" ] || { [ -d "$backup" ] && mv "$backup" "$app"; }; open "$app"; exit 1; }
        backup="${app%.app}.previous.app"; stage="${app%.app}.updating.app"
        [ -d "$src" ] || fail "update image has no MultiMini.app"
        codesign --verify --deep --strict "$src" || fail "update signature check failed"
        rm -rf "$stage" "$backup"
        ditto "$src" "$stage" || fail "copy failed"
        hdiutil detach "$mnt" -quiet
        mv "$app" "$backup" || fail "could not move the current app"
        if ! mv "$stage" "$app"; then mv "$backup" "$app"; echo "install failed, restored"; open "$app"; exit 1; fi
        xattr -dr com.apple.quarantine "$app" 2>/dev/null
        rm -rf "$backup" "$dmg"
        open "$app"
        """;

    static void install(JFrame owner, GithubUpdater.PortableRelease release, Runnable save, Runnable exit) {
        Path bundle;
        try { bundle = appBundle(); } catch (IOException error) { GithubUpdater.notice(owner, error.getMessage()); return; }
        if (release.assetUrl().isEmpty() || release.sumUrl().isEmpty()) {
            GithubUpdater.notice(owner, "This release has no verified macOS download yet.");
            return;
        }
        if (!Files.isWritable(bundle.getParent())) {
            GithubUpdater.notice(owner, "MultiMini can't replace itself in " + bundle.getParent() + ".\nMove MultiMini into your Applications folder, or update from the .dmg on GitHub Releases.");
            return;
        }
        JDialog progress = GithubUpdater.progressDialog(owner, "Downloading and verifying update…");
        new SwingWorker<Path, Void>() {
            protected Path doInBackground() throws Exception {
                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL).build();
                String sidecar = client.send(HttpRequest.newBuilder(URI.create(release.sumUrl())).timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "MultiMini-updater").build(), HttpResponse.BodyHandlers.ofString()).body();
                String expected = expectedHash(sidecar, release.assetName());
                Path stage = Files.createTempDirectory(cache(), "update-");
                Path dmg = stage.resolve(release.assetName());
                var response = client.send(HttpRequest.newBuilder(URI.create(release.assetUrl())).timeout(Duration.ofMinutes(10))
                    .header("User-Agent", "MultiMini-updater").build(), HttpResponse.BodyHandlers.ofFile(dmg));
                if (response.statusCode() != 200) throw new IOException("Download failed (" + response.statusCode() + ")");
                if (!digest(dmg).equals(expected)) { Files.deleteIfExists(dmg); throw new IOException("Download checksum mismatch; nothing was changed."); }
                Path helper = stage.resolve("apply-update.sh");
                Files.writeString(helper, HELPER, StandardCharsets.UTF_8);
                return dmg;
            }
            protected void done() {
                progress.dispose();
                try {
                    Path dmg = get();
                    save.run();
                    Path helper = dmg.resolveSibling("apply-update.sh");
                    new ProcessBuilder("/bin/bash", helper.toString(), Long.toString(ProcessHandle.current().pid()), dmg.toString(), bundle.toString())
                        .redirectErrorStream(true).redirectOutput(dmg.resolveSibling("update.log").toFile()).start();
                    exit.run();
                } catch (Exception error) {
                    GithubUpdater.notice(owner, "Update was not applied: " + GithubUpdater.failure(error));
                }
            }
        }.execute();
    }
}
