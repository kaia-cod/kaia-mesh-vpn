# PREUVE S3 — exécution depuis nativeLibraryDir (statique, sans recompiler)
date_utc=2026-11-15T01:26:33Z
repo=/home/ubuntu/SAM-G-Agent/workspace/kaia-mesh-vpn
head=3ad1936 fix(android): resolve rebase conflicts - exec from nativeLibraryDir, English UI, CI SDK detection
status_porcelain_bytes=46

## 1. Ligne d'exécution réelle (MeshDaemon.kt)
263:            val pb = ProcessBuilder(
264-                exec.absolutePath,
265-                "--socket", socketPath,
266-                "--vip", "10.42.0.1",
267-            )
268-                .directory(ctx.filesDir)
269-                .redirectErrorStream(true)

## 2. Origine du chemin exécuté = exec.absolutePath (résolu par resolveBinary())
60:    private val nativeLibraryDir: String? get() = ctx.applicationInfo?.nativeLibraryDir
201:        nativeLibraryBinary()?.let { return Result.success(it) }
237:        executablePath = exec.absolutePath

## 3. Compteurs
hits nativeLibraryDir (code app)        : 25
hits 'filesDir.*meshd' code app (exec)  : 2
hits 'data/user' dans l'UI (hors comment): 0

## 4. Refus explicite d'un chemin interdit (garde-fou avant ProcessBuilder)
122:            Log.e(TAG, "refusing a forbidden exec location: ${f.absolutePath}")
247:            Log.e(TAG, "blocked exec from a forbidden location: ${exec.absolutePath}")

## 5. Fallback codeCacheDir : chmod 0755 + log explicite
139:                f.setExecutable(true, false)
186:        target.setExecutable(true, false)
189:            throw IllegalStateException("chmod 755 failed on fallback copy (canExecute=false)")
191:        Log.i(TAG, "fallback meshd ready and executable (abi=$abi, $len bytes)")

## 6. Message d'erreur utilisateur (anglais, sans chemin)
41:        const val ERR_BINARY_MISSING =
43:        const val ERR_BINARY_BROKEN =
47:        const val ERR_DAEMON_START =

## 7. PREUVE DYNAMIQUE — BinaryPaths.kt compile et execute (JVM, sans Gradle, sans build APK)
Compilateur: kotlin-compiler-embeddable 2.0.20 (cache Gradle local, aucun telechargement)
Commande : java -cp <kotlin-compiler+jars> org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -d /tmp/s3proof/out BinaryPaths.kt
puis      java -cp /tmp/s3proof/out:kotlin-stdlib ProofKt

[PASS] chemin produit par nativeLibBinary()                       got=true attendu=true
[PASS] isAllowed(nativeLibraryDir/libmeshd.so)                    got=true attendu=true
[PASS] isAllowed(codeCacheDir/meshd) [fallback]                   got=true attendu=true
[PASS] isAllowed(filesDir/install/meshd) [ANCIEN BUG]             got=false attendu=false
[PASS] isAllowed(filesDir/meshd)                                  got=false attendu=false
[PASS] isForbidden(filesDir/install/meshd)                        got=true attendu=true
[PASS] isForbidden(nativeLibraryDir/libmeshd.so)                  got=false attendu=false
[PASS] resolveAbi choisit une ABI embarquee                       got=true attendu=true

RESULTAT: 8/8 PASS

## 8. Conclusion
- Le binaire est execute depuis applicationInfo.nativeLibraryDir/libmeshd.so (MeshDaemon.kt:60 -> :201 -> :237 -> :264).
- .directory(ctx.filesDir) est le repertoire de TRAVAIL du process (ou vit meshd.sock), pas le chemin de l'executable.
- L'ancien chemin /data/user/0/<pkg>/install/meshd est refuse a l'execution (garde-fou MeshDaemon.kt:246-249 + BinaryPaths.isAllowedExecPath).
- Fallback codeCacheDir/meshd avec setExecutable(true,false) (chmod 755) et log explicite ; erreur si canExecute=false.
- Message utilisateur en anglais, sans chemin /data/... et sans la coquille 'mesh D'.
