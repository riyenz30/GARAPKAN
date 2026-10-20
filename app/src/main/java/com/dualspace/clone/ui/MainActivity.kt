package com.dualspace.clone.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.dualspace.clone.R
import com.dualspace.clone.data.CloneManager
import com.dualspace.clone.data.CloneStore
import com.dualspace.clone.data.GmsLinker
import com.dualspace.clone.databinding.ActivityMainBinding
import com.dualspace.clone.model.Clone
import com.dualspace.clone.model.InstanceAppItem
import com.dualspace.clone.model.VirtualSpace
import com.dualspace.clone.util.BrandCompat
import com.dualspace.clone.util.CrashLog
import com.dualspace.clone.util.DsLog
import com.dualspace.clone.util.FailureText
import com.dualspace.clone.util.Prefs
import com.dualspace.clone.util.StorageUtil
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.niunaijun.blackbox.BlackBoxCore

/** Home screen: vertical stack of instance cards (Instance 1, 2, 3...) like DualSpace. */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var instanceAdapter: InstanceCardAdapter
    private var pendingLaunch: Clone? = null
    private var pendingAfterPermission: Clone? = null

    private var storageJob: Job? = null
    private var storageForCount = -1
    private var storageCheckedAt = 0L
    private var gmsCheckedAt = 0L
    private var repairing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)

        instanceAdapter = InstanceCardAdapter(
            onAppClick = { item ->
                if (item.cloneRef != null) {
                    launchClone(item.cloneRef)
                } else {
                    launchSystemGoogleApp(item.packageName, item.userId)
                }
            },
            onAppLongClick = { item ->
                if (item.cloneRef != null) {
                    openDetail(item.cloneRef)
                }
            },
            onSpaceMenuClick = { space, anchorView ->
                showSpaceMenu(space, anchorView)
            }
        )

        b.grid.layoutManager = LinearLayoutManager(this)
        b.grid.adapter = instanceAdapter
        b.grid.setHasFixedSize(false)

        b.fab.setOnClickListener {
            startActivityForResult(Intent(this, AppPickerActivity::class.java), REQ_PICK)
        }
        b.btnGmsFix.setOnClickListener { repairGms() }
        b.gmsRow.setOnClickListener { refreshGms(force = true) }

        CloneStore.clonesLive.observe(this) { render(it) }
        GmsLinker.status.observe(this) { renderGms(it) }

        RianPopup.show(this)
    }

    override fun onResume() {
        super.onResume()
        if (!LockGate.requireUnlock(this, REQ_UNLOCK_APP)) return
        refreshStorage()
        refreshGms(force = false)
        nudgeAboutNewReports()
    }

    private fun nudgeAboutNewReports() {
        lifecycleScope.launch {
            val newest = withContext(Dispatchers.IO) { runCatching { CrashLog.newestReportTime(this@MainActivity) }.getOrDefault(0L) }
            if (newest > Prefs.lastReportSeenAt && newest > 0L) {
                Snackbar.make(b.root, R.string.msg_new_reports, Snackbar.LENGTH_LONG)
                    .setAction(R.string.action_view) { startActivity(Intent(this@MainActivity, DiagnosticsActivity::class.java)) }
                    .show()
            }
        }
    }

    // ------------------------------------------------------------------ rendering

    private fun render(all: List<Clone>) {
        val visible = if (Prefs.showHidden) all else all.filter { !it.hidden }

        // Collect all distinct userIds that have clones
        val userIds = visible.map { it.userId }.distinct().sorted().toMutableList()
        // Always show at least Instance 1 even when empty
        if (userIds.isEmpty()) userIds.add(0)

        val spaces = userIds.map { uId ->
            val spaceClones = visible.filter { it.userId == uId }
            val appsList = mutableListOf<InstanceAppItem>()

            // Always show these 3 Google packages in every instance card
            appsList.add(InstanceAppItem("com.android.vending", "Google Play", uId, isSystemGoogle = true))
            appsList.add(InstanceAppItem("com.google.android.gms", "Layanan Play", uId, isSystemGoogle = true))
            appsList.add(InstanceAppItem("com.google.android.gsf", "Framework", uId, isSystemGoogle = true))

            // Add user-cloned apps (skip if it's one of the Google packages above)
            for (c in spaceClones) {
                if (c.packageName != "com.android.vending" &&
                    c.packageName != "com.google.android.gms" &&
                    c.packageName != "com.google.android.gsf") {
                    appsList.add(InstanceAppItem(c.packageName, c.label, uId, isSystemGoogle = false, cloneRef = c))
                }
            }

            VirtualSpace(userId = uId, spaceNumber = uId + 1, apps = appsList)
        }

        instanceAdapter.submit(spaces)
        b.empty.visibility = View.GONE
        b.grid.visibility = View.VISIBLE
        b.subtitle.text = "${spaces.size} Ruang Virtual"
        refreshStorage()
    }

    // ------------------------------------------------------------------ space menu

    private fun showSpaceMenu(space: VirtualSpace, anchorView: View) {
        val popup = PopupMenu(this, anchorView)
        popup.menu.add(0, 1, 0, "Tambah App ke Instance ${space.spaceNumber}")
        popup.menu.add(0, 2, 1, "Hapus Instance ${space.spaceNumber}")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    startActivityForResult(Intent(this, AppPickerActivity::class.java), REQ_PICK)
                    true
                }
                2 -> {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Hapus Instance ${space.spaceNumber}?")
                        .setMessage("Semua aplikasi dan data di Instance ${space.spaceNumber} akan dihapus permanen.")
                        .setPositiveButton("Hapus") { _, _ -> deleteInstance(space.userId) }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun deleteInstance(userId: Int) {
        lifecycleScope.launch {
            b.progress.visibility = View.VISIBLE
            withContext(Dispatchers.IO) {
                val toDelete = CloneStore.all().filter { it.userId == userId }
                for (c in toDelete) {
                    CloneManager.delete(c)
                }
                runCatching {
                    BlackBoxCore.get().uninstallGms(userId)
                    BlackBoxCore.get().deleteUser(userId)
                }
            }
            b.progress.visibility = View.GONE
            Snackbar.make(b.root, "Instance ${userId + 1} telah dihapus", Snackbar.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------ launching Google apps

    private fun launchSystemGoogleApp(packageName: String, userId: Int) {
        b.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    GmsLinker.ensure(userId)
                    BlackBoxCore.get().launchApk(packageName, userId)
                }.getOrDefault(false)
            }
            b.progress.visibility = View.GONE
            refreshGms(force = true)
            if (!ok) {
                Snackbar.make(b.root, "Gagal membuka aplikasi di Instance ${userId + 1}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    // ------------------------------------------------------------------ storage

    private fun refreshStorage() {
        val count = CloneStore.all().size
        val stale = SystemClock.elapsedRealtime() - storageCheckedAt > 30_000
        if (count == storageForCount && !stale) return
        if (storageJob?.isActive == true) return
        storageJob = lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { CloneManager.totalStorageBytes() }.getOrDefault(0L) }
            storageForCount = count
            storageCheckedAt = SystemClock.elapsedRealtime()
            b.storage.text = getString(R.string.storage_total, StorageUtil.human(bytes))
        }
    }

    // ------------------------------------------------------------------ GMS status

    private fun refreshGms(force: Boolean) {
        val stale = SystemClock.elapsedRealtime() - gmsCheckedAt > 20_000
        if (!force && !stale && GmsLinker.lastStatus != null) return
        gmsCheckedAt = SystemClock.elapsedRealtime()
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { GmsLinker.refreshStatus() }
                .onFailure { DsLog.w("MainActivity", "GMS status refresh failed", it) }
        }
    }

    private fun renderGms(s: GmsLinker.Status) {
        if (s.connected) {
            b.dotGms.setImageResource(R.drawable.dot_green)
            b.gmsStatus.text = getString(R.string.gms_status_connected)
            b.btnGmsFix.visibility = View.GONE
            return
        }
        b.dotGms.setImageResource(R.drawable.dot_red)
        val reason = when {
            !s.hostHasGms -> getString(R.string.gms_reason_no_host)
            !s.engineReachable -> getString(R.string.gms_reason_engine)
            else -> getString(R.string.gms_reason_unlinked, s.unlinkedSlots.size)
        }
        b.gmsStatus.text = getString(R.string.gms_status_disconnected) + "\n" + reason
        b.btnGmsFix.visibility = if (s.hostHasGms && !repairing) View.VISIBLE else View.GONE
    }

    private fun repairGms() {
        if (repairing) return
        repairing = true
        b.btnGmsFix.visibility = View.GONE
        b.progress.visibility = View.VISIBLE
        Snackbar.make(b.root, R.string.msg_gms_repairing, Snackbar.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) { runCatching { GmsLinker.repairAll() }.getOrNull() }
            repairing = false
            b.progress.visibility = View.GONE
            status?.let { renderGms(it) }
            val ok = status?.connected == true
            Snackbar.make(b.root, if (ok) R.string.msg_gms_repaired else R.string.msg_gms_repair_failed, Snackbar.LENGTH_LONG)
                .apply { if (!ok) setAction(R.string.action_details) { startActivity(Intent(this@MainActivity, DiagnosticsActivity::class.java)) } }
                .show()
        }
    }

    // ------------------------------------------------------------------ launching clones

    private fun launchClone(clone: Clone) {
        if (clone.frozen) {
            Snackbar.make(b.root, R.string.msg_frozen, Snackbar.LENGTH_SHORT)
                .setAction(R.string.action_unfreeze) {
                    lifecycleScope.launch(Dispatchers.IO) { runCatching { CloneManager.setFrozen(clone, false) } }
                }
                .show()
            return
        }
        if (clone.locked) {
            pendingLaunch = clone
            if (!LockGate.requireUnlock(this, REQ_UNLOCK_CLONE, force = true)) return
        }
        doLaunch(clone)
    }

    private fun doLaunch(clone: Clone) {
        if (!BrandCompat.hasLocationAccess(this) && !Prefs.locationAsked) {
            Prefs.locationAsked = true
            pendingAfterPermission = clone
            ActivityCompat.requestPermissions(this, BrandCompat.LOCATION_PERMISSIONS, REQ_LOCATION)
            return
        }
        launchNow(clone)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            DsLog.i("MainActivity", "location permission for clones: granted=${BrandCompat.hasLocationAccess(this)}")
            val c = pendingAfterPermission; pendingAfterPermission = null
            if (c != null) launchNow(c)
        }
    }

    private fun launchNow(clone: Clone) {
        b.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { CloneManager.launch(clone) }
                    .getOrElse { CloneManager.LaunchResult.Failed(FailureText.describe(it)) }
            }
            b.progress.visibility = View.GONE
            if (result is CloneManager.LaunchResult.Failed) {
                Snackbar.make(b.root, getString(R.string.msg_launch_failed_reason, result.reason), Snackbar.LENGTH_LONG)
                    .setAction(R.string.action_details) {
                        startActivity(Intent(this@MainActivity, DiagnosticsActivity::class.java))
                    }
                    .show()
            }
            refreshGms(force = true)
        }
    }

    private fun openDetail(clone: Clone) {
        startActivity(Intent(this, CloneDetailActivity::class.java).putExtra(CloneDetailActivity.EXTRA_ID, clone.id))
    }

    // ------------------------------------------------------------------ menu & activity result

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_UNLOCK_APP -> if (resultCode != Activity.RESULT_OK) finish()
            REQ_UNLOCK_HIDDEN -> if (resultCode == Activity.RESULT_OK) toggleHidden()
            REQ_UNLOCK_CLONE -> {
                val c = pendingLaunch; pendingLaunch = null
                if (resultCode == Activity.RESULT_OK && c != null) doLaunch(c)
            }
            REQ_PICK -> if (resultCode == Activity.RESULT_OK) {
                val id = data?.getStringExtra(AppPickerActivity.RESULT_CLONE_ID) ?: return
                val clone = CloneStore.byId(id) ?: return
                Snackbar.make(b.root, getString(R.string.msg_clone_created, clone.label), Snackbar.LENGTH_LONG)
                    .setAction(R.string.action_open) { launchClone(clone) }
                    .show()
                refreshGms(force = true)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_show_hidden).isChecked = Prefs.showHidden
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_show_hidden -> {
            if (!Prefs.showHidden && Prefs.hasPin()) {
                if (LockGate.requireUnlock(this, REQ_UNLOCK_HIDDEN, force = true)) toggleHidden()
            } else toggleHidden()
            true
        }
        R.id.action_setup -> { startActivity(Intent(this, SetupWizardActivity::class.java)); true }
        R.id.action_diagnostics -> { startActivity(Intent(this, DiagnosticsActivity::class.java)); true }
        R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun toggleHidden() {
        Prefs.showHidden = !Prefs.showHidden
        invalidateOptionsMenu()
        render(CloneStore.all())
    }

    companion object {
        private const val REQ_PICK = 1
        private const val REQ_UNLOCK_APP = 2
        private const val REQ_UNLOCK_CLONE = 3
        private const val REQ_UNLOCK_HIDDEN = 4
        private const val REQ_LOCATION = 5
    }
}
