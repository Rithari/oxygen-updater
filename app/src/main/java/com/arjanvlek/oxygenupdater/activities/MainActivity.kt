package com.arjanvlek.oxygenupdater.activities

import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.view.MenuItem
import android.view.View
import android.widget.CheckBox
import android.widget.Toast
import androidx.annotation.IntRange
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter
import androidx.lifecycle.Observer
import androidx.viewpager.widget.ViewPager
import com.arjanvlek.oxygenupdater.ActivityLauncher
import com.arjanvlek.oxygenupdater.OxygenUpdater
import com.arjanvlek.oxygenupdater.OxygenUpdater.Companion.buildAdRequest
import com.arjanvlek.oxygenupdater.R
import com.arjanvlek.oxygenupdater.dialogs.MessageDialog
import com.arjanvlek.oxygenupdater.fragments.DeviceInformationFragment
import com.arjanvlek.oxygenupdater.fragments.NewsFragment
import com.arjanvlek.oxygenupdater.fragments.UpdateInformationFragment
import com.arjanvlek.oxygenupdater.internal.KotlinCallback
import com.arjanvlek.oxygenupdater.internal.settings.SettingsManager
import com.arjanvlek.oxygenupdater.internal.settings.SettingsManager.Companion.PROPERTY_AD_FREE
import com.arjanvlek.oxygenupdater.models.DeviceOsSpec
import com.arjanvlek.oxygenupdater.utils.NotificationTopicSubscriber
import com.arjanvlek.oxygenupdater.utils.ThemeUtils
import com.arjanvlek.oxygenupdater.utils.Utils
import com.arjanvlek.oxygenupdater.viewmodels.MainViewModel
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.InterstitialAd
import com.google.android.gms.ads.MobileAds
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.activity_main.appBar
import kotlinx.android.synthetic.main.activity_main.toolbar
import kotlinx.android.synthetic.main.activity_onboarding.*
import org.joda.time.LocalDateTime
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import kotlin.math.abs

class MainActivity : AppCompatActivity(), Toolbar.OnMenuItemClickListener {

    private lateinit var viewPager: ViewPager
    private lateinit var activityLauncher: ActivityLauncher

    private var newsAd: InterstitialAd? = null
    private var downloadPermissionCallback: KotlinCallback<Boolean>? = null
    private var adView: AdView? = null

    private val settingsManager by inject<SettingsManager>()
    private val mainViewModel by viewModel<MainViewModel>()

    var deviceOsSpec: DeviceOsSpec? = null
        private set

    public override fun onCreate(savedInstanceState: Bundle?) = super.onCreate(savedInstanceState).also {
        setContentView(R.layout.activity_main)

        val application = application as OxygenUpdater

        mainViewModel.fetchAllDevices().observe(this, Observer {
            deviceOsSpec = Utils.checkDeviceOsSpec(application.systemVersionProperties!!, it)

            val showDeviceWarningDialog = !settingsManager.getPreference(SettingsManager.PROPERTY_IGNORE_UNSUPPORTED_DEVICE_WARNINGS, false)

            if (showDeviceWarningDialog && !deviceOsSpec!!.isDeviceOsSpecSupported) {
                displayUnsupportedDeviceOsSpecMessage(deviceOsSpec!!)
            }
        })

        // subscribe to notification topics
        // we're doing it here, instead of [SplashActivity], because it requires the app to be setup first
        // (`deviceId`, `updateMethodId`, etc need to be saved in [SharedPreferences])
        if (!settingsManager.containsPreference(SettingsManager.PROPERTY_NOTIFICATION_TOPIC)) {
            NotificationTopicSubscriber.subscribe(application)
        }

        toolbar.setOnMenuItemClickListener(this)

        setupViewPager()

        activityLauncher = ActivityLauncher(this)

        // Set start page to Update Information Screen (middle page)
        try {
            var startPage = PAGE_UPDATE_INFORMATION
            val extras = intent?.extras

            if (extras?.containsKey(INTENT_START_PAGE) == true) {
                startPage = extras.getInt(INTENT_START_PAGE)
            }

            viewPager.currentItem = startPage
        } catch (ignored: IndexOutOfBoundsException) {
            // no-op
        }

        setupAds()

        // Remove "long" download ID in favor of "int" id
        try {
            // Do not remove this int assignment, even though the IDE warns it's unused.
            // getPreference method has a generic signature, we need to force its return type to be an int,
            // otherwise it triggers a ClassCastException (which occurs when coming from older app versions)
            // whilst not assigning it converts it to a long
            @Suppress("UNUSED_VARIABLE")
            val downloadId = settingsManager.getPreference(SettingsManager.PROPERTY_DOWNLOAD_ID, -1)
        } catch (e: ClassCastException) {
            settingsManager.deletePreference(SettingsManager.PROPERTY_DOWNLOAD_ID)
        }

        // Offer contribution to users from app versions below 2.4.0
        if (!settingsManager.containsPreference(SettingsManager.PROPERTY_CONTRIBUTE) && settingsManager.containsPreference(SettingsManager.PROPERTY_SETUP_DONE)) {
            activityLauncher.Contribute()
        }

        if (!Utils.checkNetworkConnection(applicationContext)) {
            showNetworkError()
        }
    }

    /**
     * Handles toolbar menu clicks
     *
     * @param item the menu item
     *
     * @return true if clicked
     */
    override fun onMenuItemClick(item: MenuItem) = when (item.itemId) {
        R.id.action_faq -> activityLauncher.FAQ().let { true }
        R.id.action_help -> activityLauncher.Help().let { true }
        R.id.action_settings -> activityLauncher.Settings().let { true }
        R.id.action_contribute -> activityLauncher.Contribute().let { true }
        R.id.action_about -> activityLauncher.About().let { true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun setupViewPager() = viewpager.apply {
        viewPager = this

        offscreenPageLimit = 2
        adapter = SectionsPagerAdapter(supportFragmentManager)
        tabs.setupWithViewPager(this)

        addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageScrollStateChanged(state: Int) {}

            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {}

            override fun onPageSelected(position: Int) {
                when (position) {
                    0, 1 -> hideTabBadge(position, 1000)
                    else -> {
                        // no-op
                    }
                }
            }
        })

        appBar.addOnOffsetChangedListener(AppBarLayout.OnOffsetChangedListener { _, verticalOffset ->
            updateLayoutParams<CoordinatorLayout.LayoutParams> {
                bottomMargin = appBar.totalScrollRange - abs(verticalOffset)
            }
        })
    }

    /**
     * Update the state of a [Tab](com.google.android.material.tabs.TabLayout.Tab)'s [BadgeDrawable](com.google.android.material.badge.BadgeDrawable)
     *
     * @param position position of the tab/fragment
     * @param show flag to control the badge's visibility
     * @param count optional number to display in the badge
     *
     * @see hideTabBadge
     */
    fun updateTabBadge(
        @IntRange(from = 0, to = 2) position: Int,
        show: Boolean = true,
        count: Int? = null
    ) = tabs.getTabAt(position)?.orCreateBadge?.apply {
        isVisible = show

        if (isVisible) {
            backgroundColor = if (ThemeUtils.isNightModeActive(this@MainActivity)) {
                ContextCompat.getColor(this@MainActivity, R.color.colorPrimary)
            } else {
                Color.WHITE
            }

            if (count != null /*&& count != 0*/) {
                badgeTextColor = ContextCompat.getColor(this@MainActivity, R.color.foreground)
                number = count
                maxCharacterCount = 3
            }
        }
    }

    /**
     * Hide the [Tab]](com.google.android.material.tabs.TabLayout.Tab)'s [BadgeDrawable](com.google.android.material.badge.BadgeDrawable) after a specified delay
     *
     * Even though [updateTabBadge] can be used to hide a badge, this function is different because it only hides an existing badge, after a specified delay.
     * It's meant to be called from the [viewPager]'s `onPageSelected` callback, within this class.
     * [updateTabBadge] can be called from child fragments to hide the badge immediately, for example, if required after refreshing
     *
     * @param position position of the tab/fragment
     * @param delayMillis the delay, in milliseconds
     *
     * @see updateTabBadge
     */
    @Suppress("SameParameterValue")
    private fun hideTabBadge(
        @IntRange(from = 0, to = 2)
        position: Int,
        delayMillis: Long = 0
    ) = tabs.getTabAt(position)?.badge?.apply {
        Handler().postDelayed({
            isVisible = false
        }, delayMillis)
    }

    /**
     * Checks for Play Services and initialises [MobileAds] if found
     */
    private fun setupAds() {
        val application = application as OxygenUpdater

        if (!application.checkPlayServices(this, false)) {
            Toast.makeText(this, getString(R.string.notification_no_notification_support), Toast.LENGTH_LONG).show()
        }

        if (!settingsManager.getPreference(PROPERTY_AD_FREE, false)) {
            InterstitialAd(this).apply {
                newsAd = this

                adUnitId = getString(R.string.advertising_interstitial_unit_id)
                loadAd(buildAdRequest())
            }
        }

        adView = updateInformationAdView
        Utils.checkAdSupportStatus(this) { adsAreSupported ->
            if (adsAreSupported) {
                adView?.apply {
                    isVisible = true
                    loadAd(buildAdRequest())
                    adListener = object : AdListener() {
                        override fun onAdLoaded() {
                            super.onAdLoaded()

                            // need to add spacing between ViewPager contents and the AdView to avoid overlapping the last item
                            // Since the AdView's size is SMART_BANNER, bottom padding should be exactly the AdView's height,
                            // which can only be calculated once the AdView has been drawn on the screen
                            post { viewPager.updatePadding(bottom = height) }
                        }
                    }
                }
            } else {
                adView?.isVisible = false

                // reset viewPager padding
                viewPager.setPadding(0, 0, 0, 0)
            }
        }
    }

    public override fun onResume() = super.onResume().also {
        adView?.resume()
    }

    public override fun onPause() = super.onPause().also {
        adView?.pause()
    }

    public override fun onDestroy() = super.onDestroy().also {
        adView?.destroy()
    }

    fun displayUnsupportedDeviceOsSpecMessage(deviceOsSpec: DeviceOsSpec) {
        // Do not show dialog if app was already exited upon receiving of devices from the server.
        if (isFinishing) {
            return
        }

        val resourceId = when (deviceOsSpec) {
            DeviceOsSpec.CARRIER_EXCLUSIVE_OXYGEN_OS -> R.string.carrier_exclusive_device_warning_message
            DeviceOsSpec.UNSUPPORTED_OXYGEN_OS -> R.string.unsupported_device_warning_message
            DeviceOsSpec.UNSUPPORTED_OS -> R.string.unsupported_os_warning_message
            else -> R.string.unsupported_os_warning_message
        }

        val checkBoxView = View.inflate(this@MainActivity, R.layout.message_dialog_checkbox, null)

        MaterialAlertDialogBuilder(this)
            .setView(checkBoxView)
            .setTitle(getString(R.string.unsupported_device_warning_title))
            .setMessage(getString(resourceId))
            .setPositiveButton(getString(R.string.download_error_close)) { dialog, _ ->
                val checkbox = checkBoxView.findViewById<CheckBox>(R.id.unsupported_device_warning_checkbox)
                settingsManager.savePreference(SettingsManager.PROPERTY_IGNORE_UNSUPPORTED_DEVICE_WARNINGS, checkbox.isChecked)
                dialog.dismiss()
            }
            .show()
    }

    private fun showNetworkError() {
        if (!isFinishing) {
            MessageDialog(
                this,
                title = getString(R.string.error_app_requires_network_connection),
                message = getString(R.string.error_app_requires_network_connection_message),
                negativeButtonText = getString(R.string.download_error_close),
                cancellable = false
            ).show()
        }
    }

    fun getNewsAd() = when {
        newsAd != null -> newsAd
        mayShowNewsAd() -> {
            InterstitialAd(this).apply {
                adUnitId = getString(R.string.advertising_interstitial_unit_id)
                loadAd(buildAdRequest())

                newsAd = this
                newsAd
            }
        }
        else -> null
    }

    fun mayShowNewsAd() = !settingsManager.getPreference(PROPERTY_AD_FREE, false)
            && LocalDateTime.parse(settingsManager.getPreference(SettingsManager.PROPERTY_LAST_NEWS_AD_SHOWN, "1970-01-01T00:00:00.000"))
        .isBefore(LocalDateTime.now().minusMinutes(5))

    fun requestDownloadPermissions(callback: KotlinCallback<Boolean>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            downloadPermissionCallback = callback
            requestPermissions(
                arrayOf(DOWNLOAD_FILE_PERMISSION, VERIFY_FILE_PERMISSION), PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(permsRequestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (permsRequestCode == PERMISSION_REQUEST_CODE && grantResults.isNotEmpty()) {
            downloadPermissionCallback?.invoke(grantResults[0] == PERMISSION_GRANTED)
        }
    }

    // Android 6.0 Run-time permissions
    fun hasDownloadPermissions() = ContextCompat.checkSelfPermission(this, VERIFY_FILE_PERMISSION) == PERMISSION_GRANTED
            && ContextCompat.checkSelfPermission(this, DOWNLOAD_FILE_PERMISSION) == PERMISSION_GRANTED

    /**
     * A [FragmentPagerAdapter] that returns a fragment corresponding to one of the sections/tabs/pages.
     */
    inner class SectionsPagerAdapter internal constructor(fragmentManager: FragmentManager) : FragmentPagerAdapter(fragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {
        override fun getItem(position: Int): Fragment {
            // getItem is called to instantiate the fragment for the given page.
            return when (position) {
                PAGE_NEWS -> NewsFragment()
                PAGE_UPDATE_INFORMATION -> UpdateInformationFragment()
                PAGE_DEVICE_INFORMATION -> DeviceInformationFragment()
                else -> TODO()
            }
        }

        override fun getCount(): Int {
            // Show 3 total pages.
            return 3
        }

        override fun getPageTitle(position: Int): CharSequence? {
            return when (position) {
                PAGE_NEWS -> getString(R.string.news)
                PAGE_UPDATE_INFORMATION -> getString(R.string.update_information_header_short)
                PAGE_DEVICE_INFORMATION -> getString(R.string.device_information_header_short)
                else -> null
            }
        }
    }

    companion object {
        private const val INTENT_START_PAGE = "start_page"
        private const val PAGE_NEWS = 0
        private const val PAGE_UPDATE_INFORMATION = 1
        private const val PAGE_DEVICE_INFORMATION = 2

        // Permissions constants
        private const val DOWNLOAD_FILE_PERMISSION = "android.permission.WRITE_EXTERNAL_STORAGE"
        const val PERMISSION_REQUEST_CODE = 200
        const val VERIFY_FILE_PERMISSION = "android.permission.READ_EXTERNAL_STORAGE"
    }
}