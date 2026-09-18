package me.phie.tawc.install

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import me.phie.tawc.R
import me.phie.tawc.install.distro.Distro
import me.phie.tawc.install.distro.DistroRegistry
import me.phie.tawc.ops.LogScreenActivity
import me.phie.tawc.ui.buildChildScreen
import me.phie.tawc.ui.primaryButton
import me.phie.tawc.ui.tonalButton
import me.phie.tawc.ui.verticalLp
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * "Install new distro" screen. Form-only: distro / label / method /
 * cache-proxy controls plus an Install button. Tapping Install kicks
 * off [InstallationService] and hands the user off to
 * [LogScreenActivity] for the live progress view, then finishes itself
 * — so the back stack is `home → log`, not `home → form → log`.
 *
 * Mutating an installation never happens as a side-effect of opening
 * this screen. The button press is the only trigger; CLI install /
 * uninstall lives on the dev exec broker (see `InstallActions`).
 * This was the
 * `install-uninstall-trigger-via-activity-launch` issue's resolution.
 */
class InstallActivity : AppCompatActivity() {

    private val store by lazy { InstallationStore(this) }
    private var selectedMethod: String? = null
    private var selectedDistro: String? = null

    /** Whether the form is in "attach an existing rootfs" mode (the
     *  linux X external-rootfs feature). When on, the distro / method /
     *  binds / proxy rows hide and a path field takes their place. */
    private var attachMode: Boolean = false
    private lateinit var attachToggle: CheckBox
    private lateinit var attachPathField: EditText
    private var attachPathRow: LinearLayout? = null
    private var savedAttachPath: String? = null

    /** Import-from-file toggle + path row (chroot-root archive extract
     *  to /data/local/<name>). Mutually exclusive with attach mode. */
    private var importMode: Boolean = false
    private lateinit var importToggle: CheckBox
    private lateinit var importPathField: EditText
    private var importPathRow: LinearLayout? = null
    private var savedImportPath: String? = null
    /** App-staged path set by the Browse picker; typed paths use the
     *  field text directly — this is only needed when the user picked a
     *  content:// source that was copied into filesDir. */
    private var chosenImportPath: String? = null

    private val importPicker = registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        val tmpDir = File(filesDir, "import-incoming").apply { mkdirs() }
        val safe = uri.lastPathSegment
            ?.replace("[^A-Za-z0-9._-]".toRegex(), "_")
            ?.lowercase(Locale.US)
            ?.take(48)
            ?.ifEmpty { "rootfs" }
        val out = File(tmpDir, "rootfs-${System.currentTimeMillis()}-${safe}")
        // Rootfs archives can be giga-scale; copy off the main thread
        // and only touch views from the UI thread once it lands.
        Thread {
            val result = runCatching {
                contentResolver.openInputStream(uri)?.use { ins ->
                    out.outputStream().use { outs -> ins.copyTo(outs) }
                }
                if (out.length() == 0L) throw IOException("empty file")
            }
            runOnUiThread {
                result.fold(
                    onSuccess = {
                        chosenImportPath = out.absolutePath
                        if (::importPathField.isInitialized) importPathField.setText(chosenImportPath)
                        revalidate()
                    },
                    onFailure = { e ->
                        android.widget.Toast.makeText(
                            this,
                            getString(R.string.install_import_read_failed, e.message),
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    },
                )
            }
        }.start()
    }

    // Row refs captured during build so [updateExternalRows] can show /
    // hide the "create a new install" sections when attach mode flips.
    private var distroPickerView: View? = null
    private var methodPickerView: View? = null
    private var helpLinkView: View? = null
    private var changeableLaterView: View? = null
    private var cacheProxyRowView: View? = null

    /** (distro key, radio) for every rendered distro option; the
     *  picker manages exclusivity across the supported/other split
     *  itself. See [buildDistroPicker]. */
    private val distroRadios = mutableListOf<Pair<String, RadioButton>>()
    private var otherDistrosExpanded = false
    private var otherDistroList: LinearLayout? = null
    private var otherDistroToggle: MaterialButton? = null
    private var labelEdited: Boolean = false

    /**
     * Bootstrap-flavor pick, as a [me.phie.tawc.install.distro.BootstrapFlavor.id]
     * string. Null means "the distro's supported flavor" (the only
     * possibility in release builds — the row only renders when the
     * build ships >1 flavor for the selected distro). Reset to null
     * when the distro selection changes.
     */
    private var selectedBootstrap: String? = null
    private var bootstrapRow: LinearLayout? = null

    /**
     * External-storage binds the install starts with (see
     * notes/external-binds.md). Starts empty, edited via
     * [ManageBindsActivity], passed to the service as JSON. Only
     * meaningful for tawcroot installs — the row hides (and
     * [beginInstall] passes null) for other methods.
     */
    private val pendingBinds = mutableListOf<ExternalBind>()
    private var bindsRow: LinearLayout? = null
    private var bindsCountLabel: TextView? = null
    private val manageBinds = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val json = result.data?.getStringExtra(ManageBindsActivity.EXTRA_BINDS)
        if (result.resultCode == RESULT_OK && json != null) {
            pendingBinds.clear()
            pendingBinds.addAll(
                runCatching { ExternalBind.fromJsonArray(org.json.JSONArray(json)) }
                    .getOrDefault(emptyList())
            )
            updateBindsRow()
        }
    }

    /**
     * Tri-state for the "Use cache proxy" checkbox:
     *   - null: not yet initialised (will be seeded from build type).
     *   - true / false: user-overridden value, persisted across rotations.
     */
    private var useCacheProxy: Boolean? = null
    private lateinit var cacheProxyCheckbox: CheckBox

    /** ando (notes/ando.md) toggle. Off by default, shown for all
     *  methods; persisted across rotations. */
    private var andoEnabled: Boolean = false

    private lateinit var formScroll: ScrollView
    private lateinit var formSection: LinearLayout
    private lateinit var methodGroup: RadioGroup
    private lateinit var labelField: EditText
    private lateinit var locationLabel: TextView
    private lateinit var installButton: MaterialButton
    private lateinit var scaffold: me.phie.tawc.ui.Scaffold

    /**
     * Resolved id for the Install button. Tracks (label → slug → unique)
     * so the service-call site doesn't have to re-derive it; null when
     * the label is empty / unslugifiable / collides with an existing
     * install (Install button is also disabled in that state).
     */
    private var resolvedId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedMethod = savedInstanceState?.getString(KEY_METHOD)
        selectedDistro = savedInstanceState?.getString(KEY_DISTRO)
        selectedBootstrap = savedInstanceState?.getString(KEY_BOOTSTRAP)
        otherDistrosExpanded = savedInstanceState?.getBoolean(KEY_OTHER_DISTROS) == true
        labelEdited = savedInstanceState?.getBoolean(KEY_LABEL_EDITED) == true
        useCacheProxy = when {
            savedInstanceState?.containsKey(KEY_USE_PROXY) == true ->
                savedInstanceState.getBoolean(KEY_USE_PROXY)
            // Dev build default: on. Production: off (and the row is
            // hidden anyway, see buildCacheProxyRow).
            me.phie.tawc.BuildConfig.DEBUG -> true
            else -> false
        }
        andoEnabled = savedInstanceState?.getBoolean(KEY_ANDO) == true
        attachMode = savedInstanceState?.getBoolean(KEY_ATTACH_MODE) == true
        savedAttachPath = savedInstanceState?.getString(KEY_ATTACH_PATH)
        importMode = savedInstanceState?.getBoolean(KEY_IMPORT_MODE) == true
        savedImportPath = savedInstanceState?.getString(KEY_IMPORT_PATH)
        chosenImportPath = savedInstanceState?.getString(KEY_IMPORT_CHOSEN)
        pendingBinds.clear()
        savedInstanceState?.getString(KEY_BINDS)?.let { savedBinds ->
            pendingBinds.addAll(
                runCatching { ExternalBind.fromJsonArray(org.json.JSONArray(savedBinds)) }
                    .getOrDefault(emptyList())
            )
        }

        scaffold = buildChildScreen(getString(R.string.title_install))

        val pad = (16 * resources.displayMetrics.density).toInt()
        formSection = buildFormSection(pad, savedInstanceState?.getString(KEY_LABEL_TEXT))
        // Wrap the form in a ScrollView so the soft keyboard can lift
        // the EditText into view without ever covering the Install
        // button on a small phone. The scaffold's content column is
        // MATCH_PARENT, so the scroll view fills it; the inner
        // formSection is WRAP_CONTENT and grows naturally.
        formScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(formSection, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        scaffold.content.addView(formScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        setContentView(scaffold.root)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_LABEL_EDITED, labelEdited)
        // Guard the late-init lookup the same way [revalidate] does —
        // saving state can in principle fire before the form is built
        // if a future onCreate path bails early.
        if (::labelField.isInitialized) {
            outState.putString(KEY_LABEL_TEXT, labelField.text.toString())
        }
        selectedMethod?.let { outState.putString(KEY_METHOD, it) }
        selectedDistro?.let { outState.putString(KEY_DISTRO, it) }
        selectedBootstrap?.let { outState.putString(KEY_BOOTSTRAP, it) }
        outState.putBoolean(KEY_OTHER_DISTROS, otherDistrosExpanded)
        useCacheProxy?.let { outState.putBoolean(KEY_USE_PROXY, it) }
        outState.putBoolean(KEY_ANDO, andoEnabled)
        outState.putBoolean(KEY_ATTACH_MODE, attachMode)
        if (::attachPathField.isInitialized) {
            outState.putString(KEY_ATTACH_PATH, attachPathField.text.toString())
        }
        outState.putBoolean(KEY_IMPORT_MODE, importMode)
        if (::importPathField.isInitialized) {
            outState.putString(KEY_IMPORT_PATH, importPathField.text.toString())
        }
        chosenImportPath?.let { outState.putString(KEY_IMPORT_CHOSEN, it) }
        outState.putString(KEY_BINDS, ExternalBind.toJsonArray(pendingBinds).toString())
    }

    private fun buildFormSection(pad: Int, savedLabelText: String?): LinearLayout {
        val s = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // List the distros that match the host's primary ABI. Empty
        // list means no Distro supports this device; render an
        // explanatory line rather than a dead radio group, and let
        // the service-level gate refuse the install if the user taps
        // anyway.
        val available = DistroRegistry.availableForHost()

        // Attach-mode toggle: "use an existing rootfs" instead of
        // installing fresh. Root-only, ships in release (needs the
        // chroot method).
        s.addView(buildAttachToggle(), verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad))

        // Import-mode toggle: "install from a rootfs file" (root-only
        // chroot extract to /data/local/<name>). Mutually exclusive
        // with attach — both are external-rootfs sources.
        s.addView(buildImportToggle(), verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad))

        val distroPicker = buildDistroPicker(available)
        distroPickerView = distroPicker
        s.addView(distroPicker, verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad))

        // The attach path row sits next to the distro picker — both are
        // "what do we run" inputs; flipping the toggle swaps one for the
        // other.
        s.addView(buildAttachPathRow(pad), verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad))
        s.addView(buildImportPathRow(pad), verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad))

        // Dev-only bootstrap-flavor radio row, between the distro
        // picker and the label field. Rendered only when the selected
        // distro has >1 flavor *in this build* — release APKs ship
        // tarball only (EnabledBootstrapFlavors), so the row never
        // appears there.
        run {
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            bootstrapRow = row
            updateBootstrapRow()
            s.addView(row, verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad))
        }

        s.addView(buildInstallDirField(available, savedLabelText), verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad))

        // Method picker + info link only render when this APK ships
        // more than one install method. The single-method case (default
        // for release: tawcroot only) hides both — there's nothing for
        // the user to choose, and the "What's the difference?" page
        // would compare a single option against nothing. Saved
        // instance state overrides the default for rotation.
        if (!EnabledMethods.onlyOne) {
            val mp = buildMethodPicker()
            methodPickerView = mp
            s.addView(mp, verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad / 2))
        } else {
            // Pin the single enabled method as the selection so
            // beginInstall doesn't fall back to tawcroot's KEY when
            // the only enabled method happens to be something else.
            selectedMethod = EnabledMethods.keys.single()
        }

        if (!EnabledMethods.onlyOne) {
            // "What's the difference?" link to the install method info
            // page. Borderless text button so it reads as a help affordance,
            // not a primary action.
            val help = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                    text = getString(R.string.install_help_methods)
                    setTextColor(getColor(R.color.tawc_accent))
                    setOnClickListener {
                        startActivity(Intent(this@InstallActivity, InstallMethodInfoActivity::class.java))
                    }
                }
            helpLinkView = help
            s.addView(
                help,
                verticalLp(WRAP_CONTENT, WRAP_CONTENT, bottomMargin = pad),
            )
        }

        // Header for the trailing post-install-editable settings (ando,
        // binds): both are also on the distro settings page, so say so
        // here and spare the user agonizing over them mid-install.
        val changeableLater = TextView(this).apply {
                text = getString(R.string.install_changeable_later)
                textSize = 13f
                alpha = 0.7f
            }
        changeableLaterView = changeableLater
        s.addView(
            changeableLater,
            verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad / 4),
        )

        // ando toggle (notes/ando.md) — shown for all methods and build
        // types, default off. Opt-in, fail-closed. Above the binds row.
        s.addView(buildAndoRow(), verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad))

        // External-storage binds row: count + Manage button. Hidden
        // when the build doesn't ship MANAGE_EXTERNAL_STORAGE, and for
        // non-tawcroot methods (the only consumer of the bind list) —
        // see updateBindsRow / the method-picker listener.
        if (AllFilesAccess.declared(this)) {
            val row = buildBindsRow()
            bindsRow = row
            s.addView(row, verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad))
            updateBindsRow()
        }

        // Dev-only "Use cache proxy" checkbox. Hidden in release builds
        // — production must never even ask the user about a localhost
        // proxy URL, since it'd never be reachable from a packaged APK.
        if (me.phie.tawc.BuildConfig.DEBUG) {
            val cc = buildCacheProxyRow()
            cacheProxyRowView = cc
            s.addView(cc, verticalLp(MATCH_PARENT, WRAP_CONTENT, bottomMargin = pad))
        }

        installButton = primaryButton(getString(R.string.action_install)) { beginInstall() }
        s.addView(installButton, verticalLp(MATCH_PARENT, WRAP_CONTENT))

        // Initial validation pass — populates resolvedId, location row,
        // and Install button enabled-state from the default label.
        updateExternalRows()
        revalidate()
        return s
    }

    /**
     * The attach-mode toggle + hidden path row builder.
     */
    private fun buildAttachToggle(): CheckBox =
        CheckBox(this).apply {
            text = getString(R.string.install_attach_toggle)
            isChecked = attachMode
            setOnCheckedChangeListener { _, checked ->
                attachMode = checked
                if (checked) {
                    importMode = false
                    importToggle.isChecked = false
                }
                updateExternalRows()
                revalidate()
            }
        }

    private fun buildImportToggle(): CheckBox =
        CheckBox(this).apply {
            text = getString(R.string.install_import_toggle)
            isChecked = importMode
            setOnCheckedChangeListener { _, checked ->
                importMode = checked
                if (checked) {
                    attachMode = false
                    attachToggle.isChecked = false
                }
                updateExternalRows()
                revalidate()
            }
        }

    private fun buildAttachPathRow(pad: Int): LinearLayout {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply {
            text = getString(R.string.install_attach_path_label)
            textSize = 14f
        }
        container.addView(title, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        attachPathField = EditText(this).apply {
            setText(savedAttachPath ?: DEFAULT_ATTACH_PATH)
            isSingleLine = true
            hint = getString(R.string.install_attach_path_label)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) { revalidate() }
            })
        }
        container.addView(attachPathField, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val note = TextView(this).apply {
            text = getString(R.string.install_attach_note)
            textSize = 12f
            alpha = 0.8f
        }
        container.addView(
            note,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = pad / 4 },
        )
        attachPathRow = container
        return container
    }

    private fun buildImportPathRow(pad: Int): LinearLayout {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pathRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        importPathField = EditText(this).apply {
            setText(savedImportPath.orEmpty())
            isSingleLine = true
            hint = getString(R.string.install_import_path_label)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    // The picker path overwrites the field; an explicit
                    // user edit wins over any picker staging path.
                    chosenImportPath = null
                    revalidate()
                }
            })
        }
        pathRow.addView(importPathField, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        pathRow.addView(tonalButton(getString(R.string.install_import_browse)) {
            importPicker.launch("*/*")
        })
        container.addView(pathRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val note = TextView(this).apply {
            text = getString(R.string.install_import_note)
            textSize = 12f
            alpha = 0.8f
        }
        container.addView(
            note,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = pad / 4 },
        )
        importPathRow = container
        return container
    }

    /** Show/hide the "new install" sections vs the attach/import row. */
    private fun updateExternalRows() {
        val externalMode = attachMode || importMode
        distroPickerView?.visibility = if (externalMode) View.GONE else View.VISIBLE
        attachPathRow?.visibility = if (attachMode) View.VISIBLE else View.GONE
        importPathRow?.visibility = if (importMode) View.VISIBLE else View.GONE
        methodPickerView?.visibility = if (externalMode) View.GONE else View.VISIBLE
        helpLinkView?.visibility = if (externalMode) View.GONE else View.VISIBLE
        changeableLaterView?.visibility = if (externalMode) View.GONE else View.VISIBLE
        cacheProxyRowView?.visibility = if (externalMode) View.GONE else View.VISIBLE
        if (externalMode) {
            // The dev-only flavor row and the binds row both derive their
            // visibility from selection state below; pin them hidden while
            // in an external-rootfs mode.
            bootstrapRow?.visibility = View.GONE
            bindsRow?.visibility = View.GONE
        } else {
            updateBootstrapRow()
            updateBindsRow()
        }
    }

    /**
     * Build the distro picker. The two supported distros
     * ([Distro.supported]) sit at the top; everything else the APK
     * ships hides behind an "Other distros" expander, collapsed
     * unless the current pick lives in there. Distros whose Android
     * ABI doesn't match the host aren't listed at all. With no
     * unsupported distros for this host it degrades to a flat list
     * (no expander).
     *
     * Radios are driven by hand rather than by a [RadioGroup] because
     * the selection spans two containers — a RadioGroup only
     * un-checks its own direct children.
     */
    private fun buildDistroPicker(available: List<Distro>): LinearLayout {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply { text = getString(R.string.install_distro_label); textSize = 14f }
        container.addView(title, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        if (available.isEmpty()) {
            val msg = TextView(this).apply {
                text = getString(R.string.install_no_supported_distro)
                textSize = 14f
                typeface = Typeface.MONOSPACE
            }
            container.addView(msg, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            return container
        }

        distroRadios.clear()
        val supported = available.filter { it.supported }
        val other = available.filterNot { it.supported }

        // available is supported-first, so the fallback pick is a
        // supported distro whenever there is one.
        val initialKey = selectedDistro?.takeIf { k -> available.any { it.key == k } }
            ?: available.first().key

        for (d in supported) {
            container.addView(distroRadio(d), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }

        if (other.isNotEmpty()) {
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            list.addView(captionView(getString(R.string.install_distro_other_note)))
            for (d in other) {
                list.addView(distroRadio(d), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            }
            otherDistroList = list
            val toggle = MaterialButton(
                this, null, com.google.android.material.R.attr.borderlessButtonStyle,
            ).apply {
                setTextColor(getColor(R.color.tawc_accent))
                setOnClickListener { setOtherDistrosExpanded(!otherDistrosExpanded) }
            }
            otherDistroToggle = toggle
            container.addView(toggle, verticalLp(WRAP_CONTENT, WRAP_CONTENT))
            container.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            // Never hide the current pick behind a collapsed expander.
            setOtherDistrosExpanded(
                otherDistrosExpanded || other.any { it.key == initialKey },
            )
        }

        selectDistro(initialKey, updateLabel = false)
        return container
    }

    /** Quiet caption line under the "Other distros" expander. */
    private fun captionView(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
    }

    /** One distro radio, registered in [distroRadios] for exclusivity. */
    private fun distroRadio(d: Distro): RadioButton {
        val rb = RadioButton(this).apply {
            id = View.generateViewId()
            text = d.displayName
            setOnClickListener { selectDistro(d.key, updateLabel = true) }
        }
        distroRadios.add(d.key to rb)
        return rb
    }

    /**
     * Make [key] the pick: check its radio, clear the rest, and (when
     * the user hasn't typed a custom label) follow the distro's default
     * label so flipping from "Arch Linux ARM" to "Debian Sid" updates
     * the install directory too.
     */
    private fun selectDistro(key: String, updateLabel: Boolean) {
        val changed = key != selectedDistro
        selectedDistro = key
        for ((k, rb) in distroRadios) rb.isChecked = (k == key)
        if (changed) {
            // Flavor picks don't transfer across distros — reset to the
            // new distro's supported default and rebuild the (dev-only)
            // radio row. Guarded on an actual change so a restored
            // selection keeps its saved flavor.
            selectedBootstrap = null
            updateBootstrapRow()
        }
        if (updateLabel && !labelEdited) {
            DistroRegistry.availableForHost().firstOrNull { it.key == key }
                ?.let { setLabelTextSilently(it.defaultLabel) }
        }
        revalidate()
    }

    private fun setOtherDistrosExpanded(expanded: Boolean) {
        otherDistrosExpanded = expanded
        otherDistroList?.visibility = if (expanded) View.VISIBLE else View.GONE
        otherDistroToggle?.text = getString(
            if (expanded) R.string.install_distro_other_hide else R.string.install_distro_other_show,
        )
    }

    /**
     * (Re)populate the dev-only bootstrap-flavor radio row for the
     * currently selected distro. Hidden entirely when this build ships
     * a single flavor for it — which is every distro in a release
     * APK. See notes/installation.md "Bootstrap flavors".
     */
    private fun updateBootstrapRow() {
        val row = bootstrapRow ?: return
        row.removeAllViews()
        val distro = DistroRegistry.availableForHost().firstOrNull { it.key == selectedDistro }
        val flavors = distro?.bootstrapFlavors?.keys.orEmpty()
        if (distro == null || flavors.size < 2) {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        row.addView(
            TextView(this).apply { text = getString(R.string.install_bootstrap_label); textSize = 14f },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT),
        )
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val idsByFlavor = mutableMapOf<Int, String>()
        for (f in flavors) {
            val rid = View.generateViewId()
            idsByFlavor[rid] = f.id
            group.addView(
                RadioButton(this).apply {
                    id = rid
                    text = if (f == distro.supportedFlavor) "${f.id} (supported)" else f.id
                },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT),
            )
        }
        row.addView(group, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val initial = selectedBootstrap ?: distro.supportedFlavor.id
        idsByFlavor.entries.firstOrNull { it.value == initial }?.let { group.check(it.key) }
        group.setOnCheckedChangeListener { _, checkedId ->
            idsByFlavor[checkedId]?.let { selectedBootstrap = it }
        }
    }

    /**
     * Build the merged Label / Install-directory block. The user-typed
     * string is the [Installation.label]; we slugify it into the on-disk
     * id and render the resulting absolute path on the line directly
     * below as a quieter monospace echo, which doubles as the hint
     * shown when the label is empty / unslugifiable / collides.
     */
    private fun buildInstallDirField(available: List<Distro>, savedLabelText: String?): LinearLayout {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply { text = getString(R.string.install_label_label); textSize = 14f }
        container.addView(title, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        val initialDefault = available.firstOrNull { it.key == selectedDistro }?.defaultLabel ?: ""
        labelField = EditText(this).apply {
            setText(savedLabelText ?: initialDefault)
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (!suppressEditedFlag) labelEdited = true
                    revalidate()
                }
            })
        }
        container.addView(labelField, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // Single quieter monospace line below the input — when valid,
        // it's the resolved absolute install path; when invalid (empty
        // / unslugifiable / collides), it's the explanation in the
        // same slot. One line of feedback instead of two.
        locationLabel = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        container.addView(locationLabel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return container
    }

    /**
     * `setText(...)` from inside the activity (e.g. when the distro
     * radio flips) must not flip [labelEdited] back to true. Wrap
     * those updates with this guard.
     */
    private var suppressEditedFlag = false

    private fun setLabelTextSilently(text: String) {
        suppressEditedFlag = true
        try {
            labelField.setText(text)
        } finally {
            suppressEditedFlag = false
        }
    }

    /**
     * Recompute resolvedId, the location row, the hint, and the
     * Install button's enabled state from the current label. Called on
     * label edits and distro flips.
     */
    private fun revalidate() {
        if (!::labelField.isInitialized) return
        val rawLabel = labelField.text.toString().trim()
        val slug = if (rawLabel.isEmpty()) null else Installation.slugifyLabel(rawLabel)
        val collides = slug != null && store.installationDir(slug).exists() && if (importMode) {
            // Import retries a stale failed/corrupt attempt for the same
            // slug from scratch (the service purges it); any other state
            // at this slug is a real existing install and still blocks.
            when (store.load(slug)?.state) {
                null -> false
                Installation.State.FAILED,
                Installation.State.CORRUPT -> false
                else -> true
            }
        } else {
            true
        }
        val pathOk = when {
            attachMode -> attachPathOk()
            importMode -> importPathOk()
            else -> true
        }
        val externalMode = attachMode || importMode
        resolvedId = slug?.takeUnless { collides }?.takeIf { pathOk }

        if (::locationLabel.isInitialized) {
            locationLabel.text = when {
                attachMode && !pathOk -> getString(R.string.install_attach_path_invalid)
                importMode && !pathOk -> getString(R.string.install_import_path_invalid)
                rawLabel.isEmpty() -> getString(R.string.install_label_empty)
                slug == null -> getString(R.string.install_label_invalid)
                collides -> getString(R.string.install_already_installed_at, store.installationDir(slug).absolutePath)
                externalMode -> when {
                    attachMode -> attachPathField.text.toString().trim()
                    else -> importPathField.text.toString().trim()
                }
                else -> store.installationDir(slug).absolutePath
            }
            val colorAttr = if (resolvedId == null) {
                com.google.android.material.R.attr.colorError
            } else {
                com.google.android.material.R.attr.colorOnSurfaceVariant
            }
            locationLabel.setTextColor(MaterialColors.getColor(locationLabel, colorAttr))
        }

        if (::installButton.isInitialized) {
            installButton.isEnabled = (resolvedId != null)
            installButton.text = getString(
                when {
                    attachMode -> R.string.action_attach
                    importMode -> R.string.action_import
                    else -> R.string.action_install
                }
            )
        }
    }

    /** Absolute, non-blank path for the "attach an existing rootfs" mode. */
    private fun attachPathOk(): Boolean {
        if (!::attachPathField.isInitialized) return false
        val p = attachPathField.text.toString().trim()
        return p.isNotEmpty() && p.startsWith("/")
    }

    /** Absolute, non-blank path for the "install from a rootfs file" mode. */
    private fun importPathOk(): Boolean {
        if (!::importPathField.isInitialized) return false
        val p = importPathField.text.toString().trim()
        return p.isNotEmpty() && p.startsWith("/")
    }

    /**
     * "External storage binds: N" + Manage button. Tapping Manage
     * round-trips [pendingBinds] through [ManageBindsActivity] so the
     * binds are settled before the install starts — they're live
     * during the installation process itself (first boot included).
     */
    private fun buildBindsRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val count = TextView(this).apply { textSize = 14f }
        bindsCountLabel = count
        row.addView(count, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(tonalButton(getString(R.string.action_manage)) {
            manageBinds.launch(
                ManageBindsActivity.intentForResult(
                    this, ExternalBind.toJsonArray(pendingBinds).toString(),
                )
            )
        })
        return row
    }

    private fun updateBindsRow() {
        bindsCountLabel?.text = getString(R.string.install_external_binds_label, pendingBinds.size)
        // Only tawcroot consumes the bind list; hide the row when the
        // user picks a debug method so the form doesn't promise binds
        // the spawn path would ignore. selectedMethod is always set
        // before the row exists (pinned for the single-method case,
        // defaulted by buildMethodPicker otherwise).
        bindsRow?.visibility =
            if (selectedMethod == TawcrootMethod.KEY) View.VISIBLE else View.GONE
    }

    /**
     * ando toggle ([buildAndoToggleRow], notes/ando.md). Off by
     * default; drives [andoEnabled], passed to the service by
     * [beginInstall]. Shown for every method and build type — unlike
     * binds, ando applies to all install methods.
     */
    private fun buildAndoRow(): LinearLayout =
        buildAndoToggleRow(this, andoEnabled) { _, checked -> andoEnabled = checked }

    /**
     * Dev-only "Use cache proxy" checkbox. Drives [useCacheProxy],
     * which gates whether [beginInstall] passes a `mirrorProxy` URL to
     * the service. See `notes/cache-proxy.md`.
     */
    private fun buildCacheProxyRow(): CheckBox {
        cacheProxyCheckbox = CheckBox(this).apply {
            text = getString(R.string.install_use_cache_proxy)
            isChecked = useCacheProxy ?: true
            setOnCheckedChangeListener { _, checked -> useCacheProxy = checked }
        }
        return cacheProxyCheckbox
    }

    /**
     * Build the method picker. One radio per build-enabled method
     * ([EnabledMethods]) in recommendation order: tawcroot first as
     * the default, proot as the established rootless fallback, chroot
     * last for rooted-only setups. Caller in [buildFormSection] omits
     * the picker entirely when only one method is enabled.
     */
    private fun buildMethodPicker(): LinearLayout {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply { text = getString(R.string.install_method_label); textSize = 14f }
        container.addView(title, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))

        methodGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val rootAvailable = Su.rootAvailable()

        // Use generateViewId() rather than hand-picked constants —
        // any literal we'd reach for in the AAPT range collides with
        // future R.id.* once we add a layout XML.
        val idByKey = mutableMapOf<String, Int>()
        val keyById = mutableMapOf<Int, String>()
        for (key in EnabledMethods.keys) {
            val rid = View.generateViewId()
            idByKey[key] = rid
            keyById[rid] = key
            val rb = RadioButton(this).apply {
                id = rid
                text = when (key) {
                    TawcrootMethod.KEY -> getString(R.string.install_method_tawcroot_recommended)
                    ProotMethod.KEY -> getString(R.string.install_method_proot)
                    ChrootMethod.KEY -> getString(R.string.install_method_chroot_requires_root)
                    else -> key
                }
                // Chroot greys out on un-rooted devices so the
                // limitation is visible at the form level.
                if (key == ChrootMethod.KEY) isEnabled = rootAvailable
            }
            methodGroup.addView(rb, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        container.addView(methodGroup, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // Initial selection: saved/intent override if it points at an
        // enabled method, else the first enabled key (tawcroot under
        // the default ordering).
        val initial = selectedMethod?.takeIf { idByKey.containsKey(it) }
            ?: EnabledMethods.keys.first()
        idByKey[initial]?.let { methodGroup.check(it) }
        selectedMethod = initial

        methodGroup.setOnCheckedChangeListener { _, checkedId ->
            keyById[checkedId]?.let { selectedMethod = it; updateBindsRow() }
        }
        return container
    }

    private fun beginInstall() {
        // Attach/import always run via the root chroot method, so root
        // is required there too; proot/tawcroot are rootless by
        // definition, so a missing-root device fails this check only if
        // the user picked chroot / attach / import mode.
        if ((attachMode || importMode || selectedMethod == ChrootMethod.KEY) && !Su.rootAvailable()) {
            // We don't have a panel anymore; surface as a quick
            // toast-style status on the form. Service-level gate would
            // also refuse, but a fail-fast at the form level avoids the
            // service start.
            android.widget.Toast.makeText(
                this,
                getString(R.string.install_root_unavailable),
                android.widget.Toast.LENGTH_LONG,
            ).show()
            return
        }

        if (importMode) {
            val targetId = resolvedId ?: return  // button disabled when null
            val path = chosenImportPath ?: importPathField.text.toString().trim()
            if (!path.startsWith("/")) return
            val labelText = labelField.text.toString().trim().takeIf { it.isNotEmpty() }
            InstallationService.startInstall(
                this, targetId, ChrootMethod.KEY, null, labelText,
                archivePath = path,
            )
            startActivity(LogScreenActivity.intentFor(this, "install:$targetId"))
            finish()
            return
        }

        if (attachMode) {
            val targetId = resolvedId ?: return  // button disabled when null
            val path = attachPathField.text.toString().trim()
            if (!path.startsWith("/")) return
            val labelText = labelField.text.toString().trim().takeIf { it.isNotEmpty() }
            InstallationService.startInstall(
                this, targetId, ChrootMethod.KEY, null, labelText,
                externalRootfsPath = path,
            )
            startActivity(LogScreenActivity.intentFor(this, "install:$targetId"))
            finish()
            return
        }

        val methodKey = selectedMethod ?: EnabledMethods.keys.firstOrNull() ?: TawcrootMethod.KEY
        val targetId = resolvedId ?: return  // button disabled when null

        val distroKey = selectedDistro
        val labelText = labelField.text.toString().trim().takeIf { it.isNotEmpty() }
        // Dev-time cache proxy URL: when the (debug-only) checkbox is
        // on, use the standard local proxy URL; else null. Service-side
        // gates this on BuildConfig.DEBUG so a release APK ignores any
        // stray value anyway.
        val mirrorProxyUrl = if (useCacheProxy == true) DEFAULT_PROXY_URL else null

        // Bind list for tawcroot installs; null for methods that
        // don't consume binds.
        val bindsJson = if (methodKey == TawcrootMethod.KEY && AllFilesAccess.declared(this)) {
            ExternalBind.toJsonArray(pendingBinds).toString()
        } else {
            null
        }

        val launch = {
            InstallationService.startInstall(
                this, targetId, methodKey, distroKey, labelText, mirrorProxyUrl, bindsJson, andoEnabled,
                selectedBootstrap,
            )
            startActivity(LogScreenActivity.intentFor(this, "install:$targetId"))
            finish()
        }

        // A shared-storage bind without the all-files grant fails
        // closed at first spawn — i.e. during the install itself. Warn
        // up front instead of letting the install run for minutes and
        // then park in FAILED.
        if (bindsJson != null &&
            AllFilesAccess.requiresGrant(pendingBinds) && !AllFilesAccess.granted()
        ) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.install_binds_grant_title))
                .setMessage(getString(R.string.install_binds_grant_message))
                .setNegativeButton(getString(R.string.install_binds_grant_anyway)) { _, _ -> launch() }
                .setPositiveButton(getString(R.string.install_binds_grant_grant)) { _, _ ->
                    AllFilesAccess.openSettings(this)
                }
                .show()
            return
        }
        launch()
    }

    companion object {
        /** URL the "Use cache proxy" checkbox sets. Debug-only. */
        private const val DEFAULT_PROXY_URL = "http://127.0.0.1:8080/proxy/"
        private const val KEY_METHOD = "tawc.install.method"
        private const val KEY_DISTRO = "tawc.install.distro"
        private const val KEY_OTHER_DISTROS = "tawc.install.otherDistrosExpanded"
        private const val KEY_LABEL_EDITED = "tawc.install.labelEdited"
        private const val KEY_LABEL_TEXT = "tawc.install.labelText"
        private const val KEY_USE_PROXY = "tawc.install.useCacheProxy"
        private const val KEY_BINDS = "tawc.install.externalBinds"
        private const val KEY_ANDO = "tawc.install.ando"
        private const val KEY_BOOTSTRAP = "tawc.install.bootstrap"
        private const val KEY_ATTACH_MODE = "tawc.install.attachMode"
        private const val KEY_ATTACH_PATH = "tawc.install.attachPath"
        private const val KEY_IMPORT_MODE = "tawc.install.importMode"
        private const val KEY_IMPORT_PATH = "tawc.install.importPath"
        private const val KEY_IMPORT_CHOSEN = "tawc.install.importChosen"
        /** Suggested default for the attach path field. */
        private const val DEFAULT_ATTACH_PATH = "/data/local/debian"
    }
}
