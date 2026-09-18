package me.phie.tawc.install

import android.content.Context
import me.phie.tawc.GraphicsBackend
import me.phie.tawc.AppPaths
import me.phie.tawc.Settings
import me.phie.tawc.compositor.CompositorService
import java.io.File

/**
 * Real-chroot implementation of [InstallationMethod]: bind mounts plus
 * `chroot(2)`, all under Magisk's `su`. The bind-mount script generator
 * lives in [ChrootMounter]; this object just routes interface calls
 * there.
 *
 * Sole consumer of [Su.run] in the install package — keeping the
 * `requiresRoot=true` quarantine here makes the rest of the install
 * pipeline method-agnostic (and in particular keeps [ProotMethod] from
 * accidentally inheriting root assumptions).
 */
class ChrootMethod(context: Context) : InstallationMethod {
    private val appPaths = AppPaths.from(context)
    private val store = InstallationStore(context)

    override val key: String = KEY
    override val displayName: String = "chroot (root)"
    override val requiresRoot: Boolean = true

    override fun isAvailable(context: Context): Boolean = Su.rootAvailable()

    override fun runOutside(
        script: String,
        onLine: ((String) -> Unit)?,
    ): MethodResult {
        val r = Su.run(script, onLine = onLine)
        return MethodResult(r.exitCode, r.output)
    }

    /**
     * Start a chroot subprocess running [command] inside [rootfs] under
     * `su`. The bind-mount + chroot setup script is piped to su via
     * stdin; the subsequent in-rootfs bash inherits that stdin pipe,
     * but by the time bash takes over we've already written everything
     * we want and the shell has consumed it (the `exec setsid chroot`
     * line replaces the shell, leaving any further stdin bytes for
     * bash).
     *
     * `setsid` upholds the rootfs-session invariant
     * (notes/rootfs-sessions.md): every chroot invocation runs in its
     * own session.
     */
    override fun startInside(rootfs: String, command: String?, graphics: GraphicsBackend?): Process {
        // Externally-attached rootfs (linux X attach feature): the tree
        // lives outside app data (e.g. /data/local/debian) and was never
        // provisioned by TawcInstaller. Detect it here so the per-spawn
        // script (a) skips writing the linker config into the user-owned
        // tree and (b) bind-supplies libhybris read-only instead of
        // relying on in-tree copies. Same asset-bind shape tawcroot uses.
        val external = store.idForRootfs(rootfs)?.let { store.load(it)?.externalRootfsPath != null } == true
        val hybrisSrc = File(context.filesDir, "libhybris").absolutePath
        if (!external) {
            LinkerConfig.install(rootfs)
        }
        // Magisk's su inherits the calling process's mount namespace,
        // so we wrap with `unshare -m` so any leaked binds go away when
        // the script exits. (See [Su.run]'s docstring for context.)
        val proc = ProcessBuilder(listOf("su", "-c", "exec unshare -m -- /system/bin/sh"))
            .start()
        // Per-distro ando socket dir (notes/ando.md), bind-mounted only
        // when ando is enabled; test-mode override honored.
        val andoHostDir = store.andoHostDir(rootfs)
        val script = buildString {
            appendLine("set -eu")
            appendLine(ChrootMounter.mountScript(rootfs, appPaths.shareDir.absolutePath, andoHostDir))
            if (external && CompositorService.ensureLibhybrisExtracted(context)) {
                val hybrisSrcQ = Sh.quote(hybrisSrc)
                val guestHybris = "$rootfs/usr/lib/hybris"
                val guestHybrisQ = Sh.quote(guestHybris)
                appendLine(
                    """
                    # Attached rootfs has no in-tree libhybris (install
                    # pipeline was skipped); supply it read-only from app
                    # assets so guest GUI apps get hardware GL without
                    # writing anything into the user-owned tree.
                    mkdir -p $guestHybrisQ
                    is_mounted $guestHybrisQ || {
                        mount -o bind,rslave $hybrisSrcQ $guestHybrisQ
                        mount -o remount,ro,bind $guestHybrisQ
                    }
                    """.trimIndent()
                )
            }
            // Quote rootfs and (if present) the user command into the
            // script. Both go through Sh.quote so paths with quotes
            // can't break out. The in-rootfs bash starts under
            // `/usr/bin/env -i KEY=VAL …` so nothing the host (or
            // Magisk's su) leaks through — the bash sees exactly
            // [RootfsEnv]'s map, with PATH/locale further refined by
            // the distro's /etc/profile.
            val rootfsQ = Sh.quote(rootfs)
            val envArgvQ = RootfsEnv.envArgv(RootfsEnv.Method.CHROOT, graphics ?: Settings.graphicsBackend)
                .joinToString(" ") { Sh.quote(it) }
            if (command != null) {
                val cmdQ = Sh.quote(command)
                appendLine("exec setsid chroot $rootfsQ $envArgvQ /bin/bash -lc $cmdQ")
            } else {
                appendLine("exec setsid chroot $rootfsQ $envArgvQ /bin/bash -l")
            }
        }
        // Write+flush; intentionally don't close (exec replaces the
        // shell so further stdin bytes flow into the in-rootfs bash).
        val w = proc.outputStream.bufferedWriter()
        w.write(script); w.write("\n"); w.flush()
        return proc
    }

    /** Delegates to [Archive.extractAsRoot] (the historical path). */
    override fun extractBootstrap(
        tarball: File,
        rootfs: String,
        format: BootstrapFormat,
        stripPrefix: String?,
        tempFifo: File,
        onLine: (String) -> Unit,
    ) {
        Archive.extractAsRoot(
            tarball = tarball,
            destDir = rootfs,
            tempFifo = tempFifo,
            stripPrefix = stripPrefix,
            onLine = onLine,
        )
    }

    companion object {
        const val KEY = "chroot"
    }
}
