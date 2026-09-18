package me.phie.tawc.install

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import me.phie.tawc.R
import me.phie.tawc.install.distro.DistroRegistry
import me.phie.tawc.ops.LogScreenActivity
import me.phie.tawc.ops.MutableOperation
import me.phie.tawc.ops.OperationProgress
import me.phie.tawc.ops.OperationStage
import me.phie.tawc.ops.OperationsRegistry
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Runs a single command inside an installed rootfs and streams the
 * combined stdout/stderr into an [me.phie.tawc.ops.Operation] so the
 * in-app [LogScreenActivity] shows live log output.
 *
 * Used by the "Run" button on [DistroInfoActivity]. Equivalent to
 * `scripts/rootfs-run.sh '<cmd>'` from the host, but routed through
 * the same [InstallationMethod.startInside] entry point in-process — no
 * broker hop required.
 *
 * Cancel sends `destroyForcibly()` to the immediate child (bash); any
 * grandchildren get reparented to init and continue until they exit on
 * their own. Same caveat as `rootfs-run.sh` Ctrl-C.
 *
 * Output is line-buffered: the child's stdio FDs are pipes, not a tty,
 * so block-buffering libc programs may withhold output until they exit
 * or flush. Same caveat as the broker / `rootfs-run.sh`.
 */
internal object RunCommandOp {

    private const val TAG = "tawc-runcmd"
    private val seq = AtomicLong(0)

    fun start(context: Context, installation: Installation, command: String) {
        val app = context.applicationContext
        val opId = "run-cmd:${installation.id}:${seq.incrementAndGet()}"
        val sink = MutableSharedFlow<String>(replay = 200, extraBufferCapacity = 1024)
        val procRef = AtomicReference<Process?>()
        val cancelled = AtomicBoolean(false)
        val terminal = AtomicBoolean(false)
        val opRef = AtomicReference<MutableOperation?>()
        fun finish(stage: OperationStage, message: String) {
            val currentOp = opRef.get() ?: return
            if (terminal.compareAndSet(false, true)) {
                terminate(currentOp, opId, sink, stage, message)
            }
        }
        val op = MutableOperation(
            id = opId,
            title = app.getString(
                R.string.operation_title_run_command,
                DistroRegistry.displayLabel(installation),
                command,
            ),
            log = sink,
            cancelHandler = {
                if (cancelled.compareAndSet(false, true)) {
                    val proc = procRef.get()
                    if (proc != null) {
                        proc.destroyForcibly()
                    } else {
                        finish(OperationStage.FAILED, app.getString(R.string.operation_status_cancelled))
                    }
                }
            },
        )
        opRef.set(op)
        OperationsRegistry.register(op)
        op.publish(OperationProgress(OperationStage.RUNNING, app.getString(R.string.operation_status_running)))
        sink.tryEmit("$ $command")

        try {
            app.startActivity(
                LogScreenActivity.intentFor(app, opId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        } catch (t: Throwable) {
            // BAL refusal etc. — the op stays registered, the per-op
            // tray notification's tap action lets the user reach the
            // panel later.
            Log.w(TAG, "open log screen: ${t.message}")
        }

        val rootfs = installation.rootfsDir(InstallationStore(app)).absolutePath

        thread(name = "tawc-runcmd-starter", isDaemon = true) {
            if (cancelled.get()) return@thread

            val method = InstallationMethod.forKey(app, installation.method)
            if (method == null) {
                if (cancelled.get()) return@thread
                finish(
                    OperationStage.FAILED,
                    app.getString(R.string.operation_status_unknown_install_method, installation.method),
                )
                return@thread
            }

            val proc: Process = try {
                UserRootfsSession.startInside(app, method, rootfs, command)
            } catch (t: Throwable) {
                val msg = if (cancelled.get()) {
                    app.getString(R.string.operation_status_cancelled)
                } else {
                    app.getString(
                        R.string.operation_status_failed_to_start,
                        t.javaClass.simpleName,
                        t.message ?: app.getString(R.string.operation_status_no_detail),
                    )
                }
                finish(OperationStage.FAILED, msg)
                return@thread
            }
            procRef.set(proc)
            if (cancelled.get()) {
                try {
                    proc.destroyForcibly()
                    proc.waitFor()
                } catch (_: Throwable) {}
                finish(OperationStage.FAILED, app.getString(R.string.operation_status_cancelled))
                return@thread
            }
            // Close stdin immediately so commands that read from it (e.g.
            // `cat` with no args) get EOF instead of blocking forever.
            try { proc.outputStream.close() } catch (_: Throwable) {}

            thread(name = "tawc-runcmd-stdout", isDaemon = true) {
                relayLines(proc.inputStream, sink)
            }
            thread(name = "tawc-runcmd-stderr", isDaemon = true) {
                relayLines(proc.errorStream, sink)
            }
            thread(name = "tawc-runcmd-waiter", isDaemon = true) {
                val exit = try { proc.waitFor() } catch (_: InterruptedException) { -1 }
                // Java's wstatus int can't reliably distinguish "exited 137"
                // from "killed by SIGKILL"; we just report what we got.
                val (stage, msg) = if (cancelled.get()) {
                    OperationStage.FAILED to app.getString(R.string.operation_status_cancelled)
                } else if (exit == 0) {
                    OperationStage.DONE to app.getString(R.string.operation_status_done)
                } else {
                    OperationStage.FAILED to app.getString(R.string.operation_status_exited_with_code, exit)
                }
                finish(stage, msg)
            }
        }
    }

    private fun relayLines(stream: InputStream, sink: MutableSharedFlow<String>) {
        try {
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { br ->
                while (true) {
                    val line = br.readLine() ?: break
                    sink.tryEmit(line)
                }
            }
        } catch (_: Throwable) {
            // EOF / process death — the waiter publishes the terminal stage.
        }
    }

    private fun terminate(
        op: MutableOperation,
        opId: String,
        sink: MutableSharedFlow<String>,
        stage: OperationStage,
        msg: String,
    ) {
        if (stage == OperationStage.FAILED) sink.tryEmit("[run] $msg")
        op.publish(OperationProgress(stage, msg))
        // Give a fresh LogScreenActivity time to bind before we drop the
        // op from the registry — otherwise commands like `echo hi` exit
        // before the activity's first onCreate frame and the user sees
        // the "no operation in flight" cold-open placeholder. Once the
        // activity has bound, terminating here just unbinds and the
        // panel keeps the frozen final state visible.
        thread(name = "tawc-runcmd-cleanup", isDaemon = true) {
            try { Thread.sleep(POST_TERMINATE_GRACE_MS) } catch (_: InterruptedException) {}
            OperationsRegistry.unregister(opId)
        }
    }

    private const val POST_TERMINATE_GRACE_MS = 2_000L
}
