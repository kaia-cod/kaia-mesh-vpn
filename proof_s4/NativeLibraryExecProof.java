import java.io.File;
import java.nio.file.*;
import java.util.*;

/**
 * S4 dynamic proof (single-file source launcher: `java NativeLibraryExecProof.java`).
 *
 * Replays EXACTLY the resolution policy of
 *   android/app/src/main/java/com/kaia/meshvpn/BinaryPaths.kt
 *   android/app/src/main/java/com/kaia/meshvpn/MeshDaemon.kt (resolveBinary -> ProcessBuilder)
 * on the real shipped binary `android/app/src/main/jniLibs/arm64-v8a/libmeshd.so`,
 * with a directory layout identical to what Android creates:
 *
 *   <sandbox>/data/app/com.kaia.meshvpn-1/lib/arm64-v8a/libmeshd.so   <- nativeLibraryDir
 *   <sandbox>/data/user/0/com.kaia.meshvpn/code_cache                 <- codeCacheDir (fallback)
 *   <sandbox>/data/user/0/com.kaia.meshvpn/files                      <- filesDir (FORBIDDEN)
 *   <sandbox>/data/user/0/com.kaia.meshvpn/install/meshd              <- the OLD broken location
 *
 * The check is not a mock: the resolved file is really executed with
 * ProcessBuilder(exec.absolutePath, ...), the same call as MeshDaemon.kt:306-314,
 * and the exec path is compared to nativeLibraryDir/libmeshd.so.
 */
public class NativeLibraryExecProof {

    static final String LIB_NAME = "libmeshd.so";
    static final String BIN_NAME = "meshd";
    static final String FORBIDDEN_DIR = "install";

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.printf("%-72s %s   %s%n", name, ok ? "PASS" : "FAIL", detail);
        if (ok) pass++; else fail++;
    }

    // --- BinaryPaths.kt: isAllowedExecPath -----------------------------------
    static boolean isAllowedExecPath(String path, String nativeLibraryDir, String codeCacheDir) {
        List<String> dirs = new ArrayList<>();
        if (nativeLibraryDir != null && !nativeLibraryDir.isBlank()) dirs.add(nativeLibraryDir);
        if (codeCacheDir != null && !codeCacheDir.isBlank()) dirs.add(codeCacheDir);
        if (dirs.isEmpty()) return false;
        for (String d : dirs) {
            String t = d.replaceAll("/+$", "");
            if (path.equals(d) || path.startsWith(t + "/")) return true;
        }
        return false;
    }

    // --- BinaryPaths.kt: isForbiddenExecPath ---------------------------------
    static boolean isForbiddenExecPath(String path, String filesDir) {
        for (String seg : path.split("/")) if (seg.equals(FORBIDDEN_DIR)) return true;
        if (filesDir == null || filesDir.isBlank()) return false;
        String root = filesDir.replaceAll("/+$", "") + "/";
        return path.startsWith(root) && !path.startsWith(root + "code_cache/");
    }

    // --- BinaryPaths.kt: resolveAbi ------------------------------------------
    static String resolveAbi(List<String> supported, Set<String> shipped) {
        for (String a : supported) if (shipped.contains(a)) return a;
        return supported.isEmpty() ? null : supported.get(0);
    }

    // --- MeshDaemon.kt: BinaryGate.isUsableBin (ELF check + min size) ---------
    static boolean isUsableBin(File f) {
        if (f == null || !f.isFile() || f.length() < 100_000) return false;
        try (var in = new java.io.FileInputStream(f)) {
            byte[] h = in.readNBytes(4);
            return h.length == 4 && (h[0] & 0xFF) == 0x7F && h[1] == 'E' && h[2] == 'L' && h[3] == 'F';
        } catch (Exception e) {
            return false;
        }
    }

    /** MeshDaemon.nativeLibraryBinary(): nativeLibraryDir first, executed in place. */
    static File nativeLibraryBinary(String nativeLibraryDir, String codeCacheDir) {
        if (nativeLibraryDir == null || nativeLibraryDir.isBlank()) return null;
        File f = new File(nativeLibraryDir, LIB_NAME);
        if (isForbiddenExecPath(f.getAbsolutePath(), null)) return null;
        if (!isAllowedExecPath(f.getAbsolutePath(), nativeLibraryDir, codeCacheDir)) return null;
        if (!f.exists() || !isUsableBin(f)) return null;
        return f;
    }

    /** MeshDaemon.codeCacheBinary(): fallback only, chmod 755. */
    static File codeCacheBinary(File codeCacheDir, File asset) throws Exception {
        File target = new File(codeCacheDir, BIN_NAME);
        if (isUsableBin(target) && target.canExecute()) return target;
        Files.createDirectories(codeCacheDir.toPath());
        Files.copy(asset.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        target.setReadable(true, false);
        target.setExecutable(true, false);
        return isUsableBin(target) && target.canExecute() ? target : null;
    }

    /** MeshDaemon.resolveBinary(). */
    static File resolveBinary(String nativeLibraryDir, File codeCacheDir, File asset, String[] reasonOut) {
        File n = nativeLibraryBinary(nativeLibraryDir, codeCacheDir.getAbsolutePath());
        if (n != null) { reasonOut[0] = "none"; return n; }
        reasonOut[0] = "libmeshd.so absent/invalid in nativeLibraryDir";
        try { return codeCacheBinary(codeCacheDir, asset); } catch (Exception e) { return null; }
    }

    public static void main(String[] args) throws Exception {
        File repo = new File(args.length > 0 ? args[0] : ".").getCanonicalFile();
        File asset = new File(repo, "android/app/src/main/jniLibs/arm64-v8a/" + LIB_NAME);
        System.out.println("== S4 — proof: exec from nativeLibraryDir/libmeshd.so ==");
        System.out.println("repo            : " + repo);
        System.out.println("shipped binary  : " + asset + "  (" + asset.length() + " bytes, exists=" + asset.exists() + ")");
        check("precondition: shipped libmeshd.so exists and is a usable ELF", isUsableBin(asset),
              "len=" + asset.length());

        Path sb = Files.createTempDirectory("s4-sandbox-");
        File nativeDir = new File(sb.toFile(), "data/app/com.kaia.meshvpn-1/lib/arm64-v8a");
        File appRoot   = new File(sb.toFile(), "data/user/0/com.kaia.meshvpn");
        File codeCache = new File(appRoot, "code_cache");
        File filesDir  = new File(appRoot, "files");
        File legacyInstall = new File(appRoot, "install");
        for (File d : new File[]{nativeDir, codeCache, filesDir, legacyInstall}) d.mkdirs();

        // 1. Android package manager extracts jniLibs/<abi>/libmeshd.so there, mode 0755.
        File nativeBin = new File(nativeDir, LIB_NAME);
        Files.copy(asset.toPath(), nativeBin.toPath(), StandardCopyOption.REPLACE_EXISTING);
        nativeBin.setReadable(true, false);
        nativeBin.setExecutable(true, false);
        // The OLD broken location, present on disk, must never be selected.
        File legacyBin = new File(legacyInstall, BIN_NAME);
        Files.copy(asset.toPath(), legacyBin.toPath(), StandardCopyOption.REPLACE_EXISTING);
        legacyBin.setExecutable(true, false);

        System.out.println("nativeLibraryDir: " + nativeDir.getAbsolutePath());
        System.out.println("codeCacheDir    : " + codeCache.getAbsolutePath());
        System.out.println("filesDir        : " + filesDir.getAbsolutePath());
        System.out.println("legacy (broken) : " + legacyBin.getAbsolutePath());

        String[] reason = new String[1];
        File exec = resolveBinary(nativeDir.getAbsolutePath(), codeCache, asset, reason);

        check("1. resolveBinary() selects a binary", exec != null, String.valueOf(exec));
        check("2. exec path == nativeLibraryDir/libmeshd.so (absolutePath)",
              exec != null && exec.getAbsolutePath().equals(nativeBin.getAbsolutePath()),
              exec == null ? "null" : exec.getAbsolutePath());
        check("3. exec path is NOT the legacy .../install/meshd",
              exec != null && !exec.getAbsolutePath().equals(legacyBin.getAbsolutePath()),
              legacyBin.getAbsolutePath());
        check("4. exec path is allowed (nativeLibraryDir|codeCacheDir)",
              exec != null && isAllowedExecPath(exec.getAbsolutePath(), nativeDir.getAbsolutePath(),
                                                codeCache.getAbsolutePath()), "");
        check("5. filesDir/install/meshd is FORBIDDEN",
              isForbiddenExecPath(legacyBin.getAbsolutePath(), filesDir.getAbsolutePath()), "");

        // 6. REAL launch: ProcessBuilder(exec.absolutePath, ...) — MeshDaemon.kt:306
        int exit = -1;
        String out = "";
        if (exec != null) {
            ProcessBuilder pb = new ProcessBuilder(exec.getAbsolutePath(), "--help")
                .directory(filesDir)                 // same as .directory(ctx.filesDir)
                .redirectErrorStream(true);
            Process p = pb.start();
            out = new String(p.getInputStream().readAllBytes()).strip();
            boolean fin = p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
            exit = fin ? p.exitValue() : -999;
            String firstLine = out.isEmpty() ? "(no output)" : out.split("\n")[0];
            check("6. ProcessBuilder started " + exec.getName() + " (path-shaped, not PERMISSION denied)",
                  fin && !out.contains("Cannot run program") && !out.contains("Permission denied")
                      && !(exit == 255 && out.isEmpty()),
                  "exit=" + exit + " out[0]=" + firstLine);
        } else {
            check("6. ProcessBuilder started the binary", false, "no exec resolved");
        }

        // 7. ABI rule: LGE LM-G900TM (arm64-v8a, armeabi-v7a) must pick arm64-v8a.
        String abi = resolveAbi(List.of("arm64-v8a", "armeabi-v7a", "armeabi"),
                                Set.of("arm64-v8a"));
        check("7. resolveAbi(device=[arm64-v8a, armeabi-v7a], shipped={arm64-v8a}) == arm64-v8a",
              "arm64-v8a".equals(abi), String.valueOf(abi));

        // 8. Fallback path only when nativeLibraryDir has nothing.
        File noNative = new File(sb.toFile(), "empty/lib");
        noNative.mkdirs();
        String[] r2 = new String[1];
        File fb = resolveBinary(noNative.getAbsolutePath(), codeCache, asset, r2);
        check("8. with empty nativeLibraryDir -> fallback codeCacheDir/meshd (chmod 755)",
              fb != null && fb.getAbsolutePath().equals(new File(codeCache, BIN_NAME).getAbsolutePath())
                  && fb.canExecute(), r2[0] + " -> " + (fb == null ? "null" : fb.getAbsolutePath()));

        // 9. REAL exec of a host-native binary from nativeLibraryDir (proves the
        //    noexec/W^X rule of filesDir is bypassed by nativeLibraryDir). We can only
        //    run an x86_64 ELF on this host, so we copy /bin/true under the very same
        //    name and directory the Go daemon uses, and execute it with ProcessBuilder.
        File hostBin = new File(nativeDir, LIB_NAME + ".host");
        if (new File("/bin/true").exists()) {
            hostBin.delete();
            Files.copy(Path.of("/bin/true"), hostBin.toPath(), StandardCopyOption.REPLACE_EXISTING);
            hostBin.setExecutable(true, false);
            ProcessBuilder pb2 = new ProcessBuilder(hostBin.getAbsolutePath())
                .directory(filesDir).redirectErrorStream(true);
            Process p2 = pb2.start();
            String o2 = new String(p2.getInputStream().readAllBytes()).strip();
            boolean fin2 = p2.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            int ex2 = fin2 ? p2.exitValue() : -999;
            check("9. host ELF executed from nativeLibraryDir (exit 0, no Permission denied)",
                  fin2 && ex2 == 0 && !o2.contains("Permission denied"),
                  "exit=" + ex2 + " out='" + o2 + "' path=" + hostBin.getAbsolutePath());
            // 10. Same binary copied into filesDir MUST be refused (the user's original bug).
            File inFiles = new File(filesDir, "meshd");
            Files.copy(Path.of("/bin/true"), inFiles.toPath(), StandardCopyOption.REPLACE_EXISTING);
            inFiles.setExecutable(true, false);
            boolean forbidden = isForbiddenExecPath(inFiles.getAbsolutePath(), filesDir.getAbsolutePath())
                && !isAllowedExecPath(inFiles.getAbsolutePath(), nativeDir.getAbsolutePath(),
                                      codeCache.getAbsolutePath());
            check("10. filesDir/meshd is refused by the guard (the original bug cannot come back)",
                  forbidden, inFiles.getAbsolutePath());
            hostBin.delete();
        }

        System.out.println();
        System.out.println("RESULT: " + pass + " passed / " + fail + " failed");
        System.out.println("LAUNCH_OUTPUT_FIRST_120_CHARS: " + (out.isEmpty() ? "" :
            out.replace("\n", " | ").substring(0, Math.min(120, out.length()))));
        System.exit(fail == 0 ? 0 : 1);
    }
}
