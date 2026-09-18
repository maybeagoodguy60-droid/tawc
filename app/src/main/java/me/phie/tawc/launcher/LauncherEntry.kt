package me.phie.tawc.launcher

import me.phie.tawc.compositor.NativeBridge
import me.phie.tawc.install.Sh
import me.phie.tawc.install.Su
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One launchable Linux application discovered inside a chroot rootfs.
 * Mirrors the JSON shape returned by [NativeBridge.nativeLauncherScan] —
 * the Rust scanner is the source of truth for what counts as launchable
 * (Type=Application, not NoDisplay/Hidden, has Exec).
 */
data class LauncherEntry(
    /** Filename minus `.desktop`, used as a stable id. */
    val id: String,
    val name: String,
    val comment: String,
    /** Exec line with field codes (`%f`, `%u`, …) already stripped. */
    val exec: String,
    val terminal: Boolean,
    /**
     * Absolute path to a PNG icon file inside the rootfs, or empty if
     * none was findable. The Rust scanner only ever returns PNGs (Android
     * can't decode SVG natively); SVG-only icons end up empty here and
     * the row renders without an icon.
     */
    val iconPath: String,
    /**
     * Absolute host path of the `.desktop` file this entry was parsed
     * from. Distinguishes managed (user-editable) entries from distro
     * ones; empty only for malformed scanner output.
     */
    val path: String = "",
) {
    companion object {
        /**
         * Scan [rootfs] with the Rust scanner and parse the result — the
         * one entry point every consumer (launcher list, shortcut
         * trampoline, debug broker) goes through. A native failure yields
         * [suScan]'s result (empty on a normal app-data rootfs); when the
         * native scan neither fails nor comes back empty it is trusted
         * verbatim. Blocking file I/O; call on
         * [kotlinx.coroutines.Dispatchers.IO] from UI code.
         */
        fun scan(rootfs: String): List<LauncherEntry> {
            val nativeJson = runCatching { NativeBridge.nativeLauncherScan(rootfs) }.getOrNull()
            val native = parseList(nativeJson)
            // Native failure or an external (attach) rootfs outside app
            // data that the app UID can't read: fall back to a root scan.
            // The chroot/attach methods are root-only anyway, so su is
            // present whenever this can help.
            if (nativeJson != null && (native.isNotEmpty() || isAppDataRootfs(rootfs))) return native
            return native + suScan(rootfs)
        }

        /**
         * Attach-only fallback: an externally-attached rootfs (linux X
         * attach feature) lives outside app data (e.g. /data/local/…)
         * and the app UID cannot read it, so the in-process Rust scanner
         * sees nothing. Shell out through `su` to enumerate/parse
         * `.desktop` files and return entries with the same JSON shape.
         * Icons are intentionally not fetched (they'd each need a
         * separate privileged read); rows render with no icon, matching
         * the "no PNG found" case.
         */
        private fun suScan(rootfs: String): List<LauncherEntry> {
            if (!Su.rootAvailable()) return emptyList()
            // Shell-quoted source text (Sh.quote escapes embedded
            // single-quotes), interpolated directly into the script so
            // quote-removal applies at parse time — never a shell
            // variable holding quotes, which would be literal.
            val rootfsQ = Sh.quote(rootfs)
            val script = """
                for d in usr/share usr/local/share root/.local/share; do
                    [ -d $rootfsQ/${'$'}d/applications ] || continue
                    for f in $rootfsQ/${'$'}d/applications/*.desktop; do
                        [ -f "${'$'}f" ] || continue
                        entry_type=${'$'}(grep -m1 '^Type=' "${'$'}f" | cut -d= -f2-)
                        [ "${'$'}entry_type" = "Application" ] || continue
                        nodisplay=${'$'}(grep -m1 '^NoDisplay=' "${'$'}f" | cut -d= -f2-)
                        case "${'$'}nodisplay" in true|True|1) continue;; esac
                        name=${'$'}(grep -m1 '^Name=' "${'$'}f" | cut -d= -f2-)
                        comment=${'$'}(grep -m1 '^Comment=' "${'$'}f" | cut -d= -f2-)
                        exec=${'$'}(grep -m1 '^Exec=' "${'$'}f" | cut -d= -f2-)
                        terminal=${'$'}(grep -m1 '^Terminal=' "${'$'}f" | cut -d= -f2-)
                        base=${'$'}(basename "${'$'}f" .desktop)
                        [ -n "${'$'}name" ] || name=${'$'}base
                        [ -n "${'$'}exec" ] || continue
                        printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
                            "${'$'}base" "${'$'}name" "${'$'}comment" \
                            "${'$'}exec" "${'$'}terminal" "${'$'}f"
                    done
                done
            """.trimIndent()
            val r = Su.run(script, timeoutSeconds = 20)
            if (!r.ok) return emptyList()
            return parseList(buildJson(r.output))
        }

        private fun isAppDataRootfs(rootfs: String): Boolean {
            val p = File(rootfs).absolutePath
            return p.startsWith("/data/user/0/") || p.startsWith("/data/data/") ||
                p.startsWith("/data_mirror/")
        }

        private fun buildJson(output: String): String {
            val arr = JSONArray()
            for (line in output.lines()) {
                val l = line.trimEnd('\r')
                if (l.isBlank()) continue
                val p = l.split("\t")
                if (p.size < 5) continue
                arr.put(
                    JSONObject()
                        .put("id", p[0])
                        .put("name", p[1])
                        .put("comment", p[2])
                        .put("exec", p[3].replace(Regex("%[a-zA-Z]"), "").trim())
                        .put("terminal", p[4].equals("true", true))
                        .put("iconPath", "")
                        .put("path", p.getOrNull(5) ?: "")
                )
            }
            return arr.toString()
        }

        /**
         * Hidden-state + search filtering, the pure core of the launcher's
         * list state. Entries with an id in [hiddenIds] are dropped unless
         * [showHidden]. [query] is a case-insensitive substring match
         * against name + id + comment; name-prefix matches sort first (so
         * typing "fire" surfaces Firefox above "WireFire"), everything
         * else keeps the scanner's name order.
         */
        fun filter(
            entries: List<LauncherEntry>,
            hiddenIds: Set<String>,
            showHidden: Boolean,
            query: String,
        ): List<LauncherEntry> {
            val visible = if (showHidden) entries else entries.filter { it.id !in hiddenIds }
            val q = query.trim().lowercase()
            if (q.isEmpty()) return visible
            val prefix = ArrayList<LauncherEntry>()
            val other = ArrayList<LauncherEntry>()
            for (e in visible) {
                val n = e.name.lowercase()
                if (n.startsWith(q)) prefix.add(e)
                else if (n.contains(q) || e.id.lowercase().contains(q) ||
                    e.comment.lowercase().contains(q)) other.add(e)
            }
            return prefix + other
        }

        fun parseList(json: String?): List<LauncherEntry> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(json)
                buildList(arr.length()) {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        add(
                            LauncherEntry(
                                id = o.optString("id"),
                                name = o.optString("name"),
                                comment = o.optString("comment"),
                                exec = o.optString("exec"),
                                terminal = o.optBoolean("terminal", false),
                                iconPath = o.optString("iconPath"),
                                path = o.optString("path"),
                            )
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }
    }
}
