/*
 * Copyright (c) 2020 Proton Technologies AG
 *
 * This file is part of ProtonMail.
 *
 * ProtonMail is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ProtonMail is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with ProtonMail. If not, see https://www.gnu.org/licenses/.
 */
package ch.protonmail.android.activities

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import ch.protonmail.android.R
import ch.protonmail.android.activities.dialogs.QuickSnoozeDialogFragment
import ch.protonmail.android.activities.multiuser.AccountManagerActivity
import ch.protonmail.android.activities.navigation.LabelWithUnreadCounter
import ch.protonmail.android.activities.navigation.NavigationViewModel
import ch.protonmail.android.activities.settings.EXTRA_CURRENT_MAILBOX_LABEL_ID
import ch.protonmail.android.activities.settings.EXTRA_CURRENT_MAILBOX_LOCATION
import ch.protonmail.android.adapters.AccountsAdapter
import ch.protonmail.android.adapters.DrawerAdapter
import ch.protonmail.android.adapters.mapLabelsToDrawerLabels
import ch.protonmail.android.adapters.setUnreadLocations
import ch.protonmail.android.api.local.SnoozeSettings
import ch.protonmail.android.api.models.DatabaseProvider
import ch.protonmail.android.api.segments.event.AlarmReceiver
import ch.protonmail.android.api.segments.event.FetchUpdatesJob
import ch.protonmail.android.contacts.ContactsActivity
import ch.protonmail.android.core.Constants
import ch.protonmail.android.core.UserManager
import ch.protonmail.android.data.local.MessageDatabase
import ch.protonmail.android.domain.entity.Id
import ch.protonmail.android.domain.entity.user.User
import ch.protonmail.android.feature.account.AccountStateManager
import ch.protonmail.android.jobs.FetchMessageCountsJob
import ch.protonmail.android.mapper.LabelUiModelMapper
import ch.protonmail.android.prefs.SecureSharedPreferences
import ch.protonmail.android.servers.notification.EXTRA_USER_ID
import ch.protonmail.android.settings.pin.ValidatePinActivity
import ch.protonmail.android.uiModel.DrawerItemUiModel
import ch.protonmail.android.uiModel.DrawerItemUiModel.Primary
import ch.protonmail.android.uiModel.DrawerItemUiModel.Primary.Static.Type
import ch.protonmail.android.uiModel.DrawerUserModel
import ch.protonmail.android.uiModel.LabelUiModel
import ch.protonmail.android.uiModel.setLabels
import ch.protonmail.android.utils.AppUtil
import ch.protonmail.android.utils.UiUtil
import ch.protonmail.android.utils.extensions.app
import ch.protonmail.android.utils.resettableLazy
import ch.protonmail.android.utils.resettableManager
import ch.protonmail.android.utils.startSplashActivity
import ch.protonmail.android.utils.ui.dialogs.DialogUtils
import ch.protonmail.android.utils.ui.dialogs.DialogUtils.Companion.showTwoButtonInfoDialog
import ch.protonmail.android.views.DrawerHeaderView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.android.synthetic.main.drawer_header.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.proton.core.account.domain.entity.Account
import me.proton.core.account.domain.entity.isReady
import me.proton.core.domain.entity.UserId
import me.proton.core.util.kotlin.unsupported
import java.util.ArrayList
import java.util.Calendar
import javax.inject.Inject
import ch.protonmail.android.api.models.User as OldUser

// region constants
const val EXTRA_FIRST_LOGIN = "extra.first.login"

const val REQUEST_CODE_ACCOUNT_MANAGER = 997
const val REQUEST_CODE_SNOOZED_NOTIFICATIONS = 555
// endregion

/**
 * Base activity that offers methods for the navigation drawer. Extend from this if your activity
 * needs support for the navigation drawer.
 */
@AndroidEntryPoint
abstract class NavigationActivity :
    BaseActivity(),
    DrawerHeaderView.IDrawerHeaderListener,
    QuickSnoozeDialogFragment.QuickSnoozeListener {

    // region views
    private val toolbar by lazy { findViewById<Toolbar>(R.id.toolbar) }
    private val drawerLayout: DrawerLayout by lazy { findViewById(R.id.drawer_layout) }
    private val navigationDrawerRecyclerView by lazy { findViewById<RecyclerView>(R.id.left_drawer_navigation) }
    private val navigationDrawerUsersRecyclerView by lazy { findViewById<RecyclerView>(R.id.left_drawer_users) }
    protected var overlayDialog: Dialog? = null
    protected lateinit var drawerToggle: ActionBarDrawerToggle
    // endregion

    /**
     * [DrawerAdapter] for the Drawer. Now all the elements in the Drawer are handled by this
     * Adapter
     */
    private val drawerAdapter = DrawerAdapter()

    /**
     * [AccountsAdapter] for the Drawer. It is used as a replacement to the default [navigationDrawerRecyclerView]
     * to display the users (logged in and recently logged out) of the application.
     */
    private val accountsAdapter = AccountsAdapter()

    /** [DrawerItemUiModel.Header] for the Drawer  */
    private var drawerHeader: DrawerItemUiModel.Header? = null

    /**
     * List of [DrawerItemUiModel] that are static from the app, with relative
     * [DrawerItemUiModel.Divider]s
     */
    private var staticDrawerItems: List<DrawerItemUiModel> = ArrayList()

    /** List of [DrawerItemUiModel.Primary.Label] for the Drawer  */
    private var drawerLabels: List<Primary.Label> = ArrayList()

    val lazyManager = resettableManager()

    val messagesDatabase by resettableLazy(lazyManager) {
        MessageDatabase.getInstance(applicationContext, userManager.requireCurrentUserId()).getDao()
    }

    @Inject
    lateinit var databaseProvider: DatabaseProvider

    @Inject
    lateinit var userManager: UserManager

    private val navigationViewModel by viewModels<NavigationViewModel>()

    protected abstract val currentMailboxLocation: Constants.MessageLocationType

    protected abstract val currentLabelId: String?

    /**
     * A lambda that holds an operation that needs to be executed after the Drawer has been closed
     *
     * Note by Davide: I guess this is a workaround for avoid the Drawer's animation stuttering
     * while the other component is loading
     * TODO: Optimize loading and remove this delay
     */
    private var onDrawerClose: () -> Unit = {}

    init {
        drawerAdapter.onItemClick = { drawerItem ->
            // Header clicked
            if (drawerItem is DrawerItemUiModel.Header) {
                onQuickSnoozeClicked()
                // Primary item clicked
            } else if (drawerItem is Primary) {
                onDrawerClose = {

                    // Static item clicked
                    if (drawerItem is Primary.Static) onDrawerStaticItemSelected(drawerItem.type)

                    // Label clicked
                    else if (drawerItem is Primary.Label) onDrawerLabelSelected(drawerItem.uiModel)
                }
                drawerAdapter.setSelected(drawerItem)
                drawerLayout.closeDrawer(GravityCompat.START)
            }
        }

        accountsAdapter.onItemClick = { account ->
            if (account is DrawerUserModel.BaseUser) {
                accountStateManager.switch(account.id)
            }
        }
    }

    protected open fun onAccountSwitched(switch: AccountStateManager.AccountSwitch) {
        val message = switch.current?.username?.takeIf { switch.previous != null }?.let {
            String.format(getString(R.string.signed_in_with), switch.current.username)
        }
        message?.let { DialogUtils.showSignedInSnack(drawerLayout, it) }
    }

    protected abstract fun onInbox(type: Constants.DrawerOptionType)

    protected abstract fun onOtherMailBox(type: Constants.DrawerOptionType)

    protected abstract fun onLabelMailBox(
        type: Constants.DrawerOptionType,
        labelId: String,
        labelName: String,
        isFolder: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        accountStateManager.state
            .flowWithLifecycle(lifecycle, Lifecycle.State.CREATED)
            .onEach {
                when (it) {
                    is AccountStateManager.State.Processing,
                    is AccountStateManager.State.PrimaryExist -> Unit
                    is AccountStateManager.State.AccountNeeded -> {
                        startSplashActivity()
                        finish()
                    }
                }
            }.launchIn(lifecycleScope)

        accountStateManager.onAccountSwitched()
            .flowWithLifecycle(lifecycle, Lifecycle.State.CREATED)
            .onEach { switch -> onAccountSwitched(switch) }
            .launchIn(lifecycleScope)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        checkUserId()
        app.startJobManager()
        mJobManager.addJobInBackground(FetchUpdatesJob())
        val alarmReceiver = AlarmReceiver()
        alarmReceiver.setAlarm(this)
    }

    override fun onStart() {
        super.onStart()
        // events updates
        mApp.bus.register(this)
    }

    override fun onStop() {
        super.onStop()
        mApp.bus.unregister(this)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode == RESULT_OK && requestCode == REQUEST_CODE_SNOOZED_NOTIFICATIONS) {
            refreshDrawerHeader(checkNotNull(userManager.currentUser))
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun checkUserId() {
        // Requested UserId match the current ?
        intent.extras?.getString(EXTRA_USER_ID)?.let { extraUserId ->
            val requestedUserId = UserId(extraUserId)
            if (requestedUserId != accountStateManager.getPrimaryUserIdValue()) {
                accountStateManager.switch(requestedUserId)
            }
            intent.extras?.remove(EXTRA_USER_ID)
        }
    }

    @JvmOverloads
    protected fun closeDrawer(ignoreIfPossible: Boolean = false): Boolean {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            var closeIt = true
            if (drawerHeaderView.state == DrawerHeaderView.State.OPENED) {
                onUserClicked(false)
                drawerHeaderView.switchState()
                if (!ignoreIfPossible) {
                    closeIt = false
                }
            }
            if (closeIt) {
                drawerLayout.closeDrawers()
            }
            return true
        }
        return false
    }

    protected fun setUpDrawer() {
        navigationViewModel.reloadDependencies()
        drawerToggle = ActionBarDrawerToggle(
            this,
            drawerLayout,
            toolbar,
            R.string.open_drawer,
            R.string.close_drawer
        )
        drawerLayout.setStatusBarBackgroundColor(
            UiUtil.scaleColor(
                getColor(R.color.dark_purple),
                0.6f,
                true
            )
        )
        drawerLayout.setDrawerShadow(R.drawable.drawer_shadow, GravityCompat.START)
        setUpInitialDrawerItems(userManager.currentLegacyUser?.isUsePin ?: false)
        refreshDrawer()

        // LayoutManager set from xml
        navigationDrawerRecyclerView.adapter = drawerAdapter

        navigationDrawerUsersRecyclerView.adapter = accountsAdapter

        drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerClosed(drawerView: View) {
                super.onDrawerClosed(drawerView)
                if (drawerHeaderView.state == DrawerHeaderView.State.OPENED) {
                    onUserClicked(false)
                    drawerHeaderView.switchState()
                }
                navigationDrawerRecyclerView!!.smoothScrollToPosition(0)
                onDrawerClose()
                onDrawerClose = {}
            }
        })

        navigationViewModel.labelsWithUnreadCounterLiveData().observe(this, CreateLabelsMenuObserver())
        navigationViewModel.locationsUnreadLiveData().observe(this, LocationsMenuObserver())

        lifecycleScope.launchWhenCreated {
            userManager.currentUser?.let { refreshDrawerHeader(it) }
        }

        setupAccountsList()
    }

    protected fun setupAccountsList() {
        navigationViewModel.reloadDependencies()
        navigationViewModel.notificationsCounts()
        navigationViewModel.notificationsCounterLiveData.observe(this) { counters ->
            lifecycleScope.launchWhenCreated {
                val accounts = accountStateManager.getSortedAccounts().first().map { account ->
                    val id = Id(account.userId.id)
                    val user = userManager.getLegacyUserOrNull(id)
                    account.toDrawerUser(account.isReady(), counters[id] ?: 0, user)
                }
                accountsAdapter.items = accounts + DrawerUserModel.Footer
            }
        }
    }

    private fun Account.toDrawerUser(
        loggedIn: Boolean,
        notificationsCount: Int,
        user: OldUser?
    ) = DrawerUserModel.BaseUser.DrawerUser(
        id = userId,
        name = username,
        emailAddress = email ?: user?.defaultAddressEmail ?: username,
        loggedIn = loggedIn,
        notifications = notificationsCount,
        notificationsSnoozed = areNotificationSnoozedBlocking(Id(userId.id)),
        displayName = user?.displayName ?: username
    )

    private suspend fun areNotificationsSnoozed(userId: Id): Boolean {
        val userPreferences = SecureSharedPreferences.getPrefsForUser(this, userId)
        with(SnoozeSettings.load(userPreferences)) {
            val shouldShowNotification = !shouldSuppressNotification(Calendar.getInstance())
            val isQuickSnoozeEnabled = snoozeQuick
            val isScheduledSnoozeEnabled = getScheduledSnooze(userPreferences)
            return isQuickSnoozeEnabled || (isScheduledSnoozeEnabled && !shouldShowNotification)
        }
    }

    @Deprecated("Use suspend function", ReplaceWith("areNotificationsSnoozed(userId)"))
    private fun areNotificationSnoozedBlocking(userId: Id): Boolean =
        runBlocking { areNotificationsSnoozed(userId) }

    private fun setUpInitialDrawerItems(isPinEnabled: Boolean) {
        val hasPin = isPinEnabled && userManager.getMailboxPin() != null

        staticDrawerItems = listOfNotNull(
            Primary.Static(Type.INBOX, R.string.inbox, R.drawable.inbox),
            Primary.Static(Type.SENT, R.string.sent, R.drawable.sent),
            Primary.Static(Type.DRAFTS, R.string.drafts, R.drawable.draft),
            Primary.Static(Type.STARRED, R.string.starred, R.drawable.starred),
            Primary.Static(Type.ARCHIVE, R.string.archive, R.drawable.archive),
            Primary.Static(Type.SPAM, R.string.spam, R.drawable.spam),
            Primary.Static(Type.TRASH, R.string.trash, R.drawable.trash),
            Primary.Static(Type.ALLMAIL, R.string.all_mail, R.drawable.allmail),
            DrawerItemUiModel.Divider,
            Primary.Static(Type.CONTACTS, R.string.contacts, R.drawable.contact),
            Primary.Static(Type.SETTINGS, R.string.settings, R.drawable.settings),
            Primary.Static(Type.REPORT_BUGS, R.string.report_bugs, R.drawable.bug),
            if (hasPin) Primary.Static(Type.LOCK, R.string.lock_the_app, R.drawable.notification_icon)
            else null,
            Primary.Static(Type.SIGNOUT, R.string.logout, R.drawable.signout)
        )
    }

    protected fun refreshDrawerHeader(currentUser: User) {
        val addresses = currentUser.addresses

        if (addresses.hasAddresses) {
            val address = checkNotNull(addresses.primary)

            val isSnoozeOn = areNotificationSnoozedBlocking(currentUser.id)
            val name = address.displayName?.s ?: address.email.s

            drawerHeader = DrawerItemUiModel.Header(name, address.email.s, isSnoozeOn)
            drawerHeaderView.setUser(name, address.email.s)
            drawerHeaderView.refresh(isSnoozeOn)
            refreshDrawer()
        }
    }

    @Deprecated(
        "Use with \"new\" User",
        ReplaceWith("refreshDrawerHeader(currentUser)"),
        DeprecationLevel.ERROR
    )
    protected fun refreshDrawerHeader(user: OldUser) {
        unsupported
    }

    /** Creates a properly formatted List for the Drawer and deliver to the Adapter  */
    fun refreshDrawer() {
        drawerAdapter.items = staticDrawerItems.setLabels(drawerLabels)
    }

    override fun onQuickSnoozeClicked() {
        val quickSnoozeDialogFragment = QuickSnoozeDialogFragment.newInstance()
        val transaction = supportFragmentManager.beginTransaction()
        transaction.add(quickSnoozeDialogFragment, quickSnoozeDialogFragment.fragmentKey)
        transaction.commitAllowingStateLoss()
    }

    override fun onUserClicked(open: Boolean) {
        navigationDrawerRecyclerView.visibility = if (open) View.GONE else View.VISIBLE
        navigationDrawerUsersRecyclerView.visibility = if (open) View.VISIBLE else View.GONE
    }

    override fun onQuickSnoozeSet(enabled: Boolean) {
        lifecycleScope.launchWhenCreated {
            drawerHeader = drawerHeader?.copy(snoozeEnabled = enabled)
            refreshDrawer()
            refreshDrawerHeader(checkNotNull(userManager.currentUser))
            setupAccountsList()
        }
    }

    protected fun reloadMessageCounts() {
        mJobManager.addJobInBackground(FetchMessageCountsJob(null))
    }

    private fun onDrawerStaticItemSelected(type: Type) {

        fun onSignOutSelected() {

            fun onLogoutConfirmed(currentUserId: Id) {
                accountStateManager.logout(currentUserId)
            }

            lifecycleScope.launch {
                val nextLoggedInUserId = userManager.getPreviousCurrentUserId()

                val (title, message) = if (nextLoggedInUserId != null) {
                    val next = userManager.getUser(nextLoggedInUserId)
                    getString(R.string.logout) to getString(R.string.logout_question_next_account, next.name.s)
                } else {
                    val current = checkNotNull(userManager.currentUser)
                    getString(R.string.log_out, current.name.s) to getString(R.string.logout_question)
                }

                showTwoButtonInfoDialog(
                    title = title,
                    message = message,
                    rightStringId = R.string.yes,
                    leftStringId = R.string.no
                ) {
                    onLogoutConfirmed(checkNotNull(userManager.currentUserId))
                }
            }
        }

        when (type) {
            Type.SIGNOUT -> onSignOutSelected()
            Type.CONTACTS -> startActivity(
                AppUtil.decorInAppIntent(Intent(this, ContactsActivity::class.java))
            )
            Type.REPORT_BUGS -> startActivity(
                AppUtil.decorInAppIntent(Intent(this, ReportBugsActivity::class.java))
            )
            Type.SETTINGS -> with(AppUtil.decorInAppIntent(Intent(this, SettingsActivity::class.java))) {
                putExtra(EXTRA_CURRENT_MAILBOX_LOCATION, currentMailboxLocation.messageLocationTypeValue)
                putExtra(EXTRA_CURRENT_MAILBOX_LABEL_ID, currentLabelId)
                startActivity(this)
            }
            Type.ACCOUNT_MANAGER -> startActivityForResult(
                AppUtil.decorInAppIntent(Intent(this, AccountManagerActivity::class.java)),
                REQUEST_CODE_ACCOUNT_MANAGER
            )
            Type.INBOX -> onInbox(type.drawerOptionType)
            Type.ARCHIVE, Type.STARRED, Type.DRAFTS, Type.SENT, Type.TRASH, Type.SPAM, Type.ALLMAIL ->
                onOtherMailBox(type.drawerOptionType)
            Type.LOCK -> {
                val user = userManager.currentLegacyUser
                if (user != null && user.isUsePin && userManager.getMailboxPin() != null) {
                    user.setManuallyLocked(true)
                    val pinIntent = AppUtil.decorInAppIntent(Intent(this, ValidatePinActivity::class.java))
                    startActivityForResult(pinIntent, REQUEST_CODE_VALIDATE_PIN)
                }
            }
            Type.LABEL -> { /* We don't need it, perhaps we could remove the value from enum */
            }
        }
    }

    private fun onDrawerLabelSelected(label: LabelUiModel) {
        val exclusive = label.type == LabelUiModel.Type.FOLDERS
        onLabelMailBox(Constants.DrawerOptionType.LABEL, label.labelId, label.name, exclusive)
    }

    private inner class CreateLabelsMenuObserver : Observer<List<LabelWithUnreadCounter>> {

        override fun onChanged(labels: List<LabelWithUnreadCounter>?) {
            if (labels == null)
                return

            // Get a mapper for create LabelUiModels. TODO this dependency could be handled better
            val mapper = LabelUiModelMapper( /* isLabelEditable */false)

            // Prepare new Labels for the Adapter
            drawerLabels = mapLabelsToDrawerLabels(mapper, labels)
            refreshDrawer()
        }
    }

    private inner class LocationsMenuObserver : Observer<Map<Int, Int>> {

        override fun onChanged(unreadLocations: Map<Int, Int>) {
            // Prepare drawer Items by injecting unreadLocations
            staticDrawerItems = staticDrawerItems.setUnreadLocations(unreadLocations)
                .toMutableList()
            refreshDrawer()
        }
    }
}
