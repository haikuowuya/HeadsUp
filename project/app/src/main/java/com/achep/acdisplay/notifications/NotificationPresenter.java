/*
 * Copyright (C) 2014 AChep@xda <artemchep@gmail.com>
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA  02110-1301, USA.
 */

package com.achep.acdisplay.notifications;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.content.Context;
import android.service.notification.StatusBarNotification;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;

import com.achep.acdisplay.App;
import com.achep.acdisplay.Config;
import com.achep.acdisplay.Device;
import com.achep.acdisplay.Operator;
import com.achep.headsup.HeadsUpManager;
import com.achep.acdisplay.blacklist.AppConfig;
import com.achep.acdisplay.blacklist.Blacklist;
import com.achep.acdisplay.utils.PackageUtils;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * Created by Artem on 27.12.13.
 */
public class NotificationPresenter implements NotificationList.OnNotificationListChangedListener {

    private static final String TAG = "NotificationPresenter";

    public static final int EVENT_BATH = 0;
    public static final int EVENT_POSTED = 1;
    public static final int EVENT_CHANGED = 2;
    public static final int EVENT_CHANGED_SPAM = 3;
    public static final int EVENT_REMOVED = 4;

    private static final int RESULT_SUCCESS = 1;
    private static final int RESULT_SPAM = -1;

    private static final int FLAG_DONT_NOTIFY_FOLLOWERS = 1;
    private static final int FLAG_DONT_WAKE_UP = 2;

    private static NotificationPresenter sNotificationPresenter;

    private final NotificationList mGList;
    private final NotificationList mLList;

    private final ArrayList<WeakReference<OnNotificationListChangedListener>> mListenersRefs;
    private final Config mConfig;
    private final Blacklist mBlacklist;

    private HeadsUpManager mHeadsUpManager;
    private boolean mHeadsUpStarted;

    /**
     * Listens to config to update notification list when needed.
     */
    private class ConfigListener implements Config.OnConfigChangedListener {

        private boolean headsUpStarted;

        @Override
        public void onConfigChanged(Config config, String key, Object value) {
            switch (key) {
                case Config.KEY_NOTIFY_MIN_PRIORITY:
                    handleLowPriorityNotificationsPreferenceChanged();
                    break;
                case Config.KEY_ENABLED:
                    Context context = config.getContext().getApplicationContext();
                    setHeadsUpEnabled(context, config.isEnabled());
                    break;
            }
        }

        private void handleLowPriorityNotificationsPreferenceChanged() {
            rebuildLocalList(new Comparator() {
                @Override
                public boolean needsRebuild(OpenNotification osbn) {
                    return osbn.getNotification().priority <= Notification.PRIORITY_LOW;
                }
            });
        }
    }

    private class BlacklistListener extends Blacklist.OnBlacklistChangedListener {

        @Override
        public void onBlacklistChanged(
                @NonNull AppConfig configNew,
                @NonNull AppConfig configOld, int diff) {
            boolean hiddenNew = configNew.isHidden();
            boolean hiddenOld = configOld.isHidden();
            boolean nonClearableEnabledNew = configNew.isNonClearableEnabled();
            boolean nonClearableEnabledOld = configOld.isNonClearableEnabled();

            // Check if something important has changed.
            if (hiddenNew != hiddenOld || nonClearableEnabledNew != nonClearableEnabledOld) {
                handlePackageVisibilityChanged(configNew.packageName);
            }
        }

        private void handlePackageVisibilityChanged(final String packageName) {
            rebuildLocalList(new Comparator() {
                @Override
                public boolean needsRebuild(OpenNotification osbn) {
                    return osbn.getPackageName().equals(packageName);
                }
            });
        }
    }

    private interface Comparator {
        public boolean needsRebuild(OpenNotification osbn);
    }

    private void rebuildLocalList(Comparator comparator) {
        for (OpenNotification n : mGList.list()) {
            if (comparator.needsRebuild(n)) {
                rebuildLocalList();
                break;
            }
        }
    }

    // //////////////////////////////////////////
    // ////////////// -- MAIN -- ////////////////
    // //////////////////////////////////////////

    public interface OnNotificationListChangedListener {

        /**
         * Callback that the list of notifications has changed.
         *
         * @param osbn  an instance of notification.
         * @param event event type:
         *              {@link #EVENT_BATH}, {@link #EVENT_POSTED},
         *              {@link #EVENT_CHANGED}, {@link #EVENT_CHANGED_SPAM},
         *              {@link #EVENT_REMOVED}
         */
        public void onNotificationListChanged(@NonNull NotificationPresenter np,
                                              @Nullable OpenNotification osbn, int event);

    }

    public void registerListener(OnNotificationListChangedListener listener) {
        // Make sure to register listener only once.
        for (WeakReference<OnNotificationListChangedListener> ref : mListenersRefs) {
            if (ref.get() == listener) {
                Log.w(TAG, "Tried to register already registered listener!");
                return;
            }
        }

        mListenersRefs.add(new WeakReference<>(listener));
    }

    public void unregisterListener(OnNotificationListChangedListener listener) {
        for (WeakReference<OnNotificationListChangedListener> ref : mListenersRefs) {
            if (ref.get() == listener) {
                mListenersRefs.remove(ref);
                return;
            }
        }

        Log.w(TAG, "Tried to unregister non-existent listener!");
    }

    private NotificationPresenter() {
        mListenersRefs = new ArrayList<>();
        mGList = new NotificationList(null);
        mLList = new NotificationList(this);
        mHeadsUpManager = new HeadsUpManager();

        if (!Device.hasJellyBeanMR2Api() || Device.hasLemonCakeApi()) {
            mGList.setMaximumSize(5);
            mLList.setMaximumSize(5);
        }

        mConfig = Config.getInstance();
        mConfig.registerListener(new ConfigListener());

        mBlacklist = Blacklist.getInstance();
        mBlacklist.registerListener(new BlacklistListener());
    }

    public synchronized static NotificationPresenter getInstance() {
        if (sNotificationPresenter == null) {
            sNotificationPresenter = new NotificationPresenter();
        }
        return sNotificationPresenter;
    }

    public void setHeadsUpEnabled(@NonNull Context context, final boolean enabled) {
        if (mHeadsUpStarted != enabled) {
            mHeadsUpStarted = enabled;

            if (enabled) {
                mHeadsUpManager.start(context);
            } else {
                mHeadsUpManager.stop();
            }
        }
    }

    /**
     * Posts notification to global list, notifies every follower
     * about this change.
     * <p><i>
     *     To create {@link OpenNotification}, use
     *     {@link OpenNotification#newInstance(StatusBarNotification)} or
     *     {@link OpenNotification#newInstance(android.app.Notification)}
     *     method.
     * </i></p>
     *
     * @see #FLAG_DONT_NOTIFY_FOLLOWERS
     * @see #FLAG_DONT_WAKE_UP
     */
    public void postNotification(
            @NonNull Context context,
            @NonNull OpenNotification n, int flags) {
        boolean globalValid = isValidForGlobal(n);
        boolean localValid = false;

        // If notification will not be added to the
        // list there's no point of loading its data.
        if (globalValid) {
            n.loadData(context);

            NotificationData data = n.getNotificationData();
            data.loadCircleIcon(n);

            localValid = isValidForLocal(n);
        }

        // Extract flags.
        boolean flagIgnoreFollowers = Operator.bitAnd(
                flags, FLAG_DONT_NOTIFY_FOLLOWERS);
        boolean flagWakeUp = !Operator.bitAnd(
                flags, FLAG_DONT_WAKE_UP);

        mGList.pushOrRemove(n, globalValid, flagIgnoreFollowers);
        mLList.pushOrRemove(n, localValid, flagIgnoreFollowers);
    }

    /**
     * Removes notification from the presenter and sends
     * this event to followers. Calling his method will not
     * remove notification from system!
     */
    public void removeNotification(@NonNull OpenNotification n) {
        mGList.remove(n);
        mLList.remove(n);
    }

    /**
     * Re-validates all notifications from {@link #mGList global list}
     * and sends {@link #EVENT_BATH bath} event after.
     *
     * @see #isValidForLocal(OpenNotification)
     * @see #isValidForGlobal(OpenNotification)
     */
    private void rebuildLocalList() {
        boolean changed = false;

        // Remove not valid notifications
        // from local list.
        ArrayList<OpenNotification> list = mLList.list();
        for (int i = 0; i < list.size(); i++) {
            OpenNotification n = list.get(i);
            if (!isValidForLocal(n)) {
                changed = true;
                list.remove(i--);
            }
        }

        // Add newly valid notifications to local list.
        for (OpenNotification n : mGList.list()) {
            if (isValidForLocal(n) && mLList.indexOf(n) == -1) {
                changed = true;
                list.add(n);
            }
        }

        if (changed) {
            notifyListeners(null, EVENT_BATH);
        }
    }

    public ArrayList<OpenNotification> getList() {
        return mLList.list();
    }

    // //////////////////////////////////////////
    // ///////////// -- EVENTS -- ///////////////
    // //////////////////////////////////////////

    @Override
    public int onNotificationAdded(@NonNull OpenNotification n) {
        notifyListeners(n, EVENT_POSTED);
        return RESULT_SUCCESS;
    }

    @Override
    public int onNotificationChanged(@NonNull OpenNotification n, @NonNull OpenNotification old) {
        // Prevent god damn notification spam by
        // checking texts' equality.

        // An example of notification spammer is well-known
        // DownloadProvider (seriously, Google?)
        NotificationData dataOld = old.getNotificationData();
        NotificationData dataNew = n.getNotificationData();

        if (dataNew.number == dataOld.number
                && TextUtils.equals(dataNew.titleText, dataOld.titleText)
                && TextUtils.equals(dataNew.titleBigText, dataOld.titleBigText)
                && TextUtils.equals(dataNew.messageText, dataOld.messageText)
                && TextUtils.equals(dataNew.infoText, dataOld.infoText)) {
            // Technically notification was changed, but it was a fault
            // of dumb developer. Mark notification as read, if old one was.
            n.getNotificationData().markAsRead(old.getNotificationData().isRead);

            if (!n.isMine()) {
                notifyListeners(n, EVENT_CHANGED_SPAM);
                return RESULT_SPAM; // Don't wake up.
            }
        }

        notifyListeners(n, EVENT_CHANGED);
        return RESULT_SUCCESS;
    }

    @Override
    public int onNotificationRemoved(@NonNull OpenNotification n) {
        notifyListeners(n, EVENT_REMOVED);
        n.recycle(); // Free all resources
        return RESULT_SUCCESS;
    }

    // //////////////////////////////////////////
    // //////// -- NOTIFICATION UTILS -- ////////
    // //////////////////////////////////////////

    private void notifyListeners(@Nullable OpenNotification n, int event) {
        for (int i = mListenersRefs.size() - 1; i >= 0; i--) {
            WeakReference<OnNotificationListChangedListener> ref = mListenersRefs.get(i);
            OnNotificationListChangedListener l = ref.get();

            if (l == null) {
                // There were no links to this listener except
                // our class.
                Log.w(TAG, "Deleting unused listener!");
                mListenersRefs.remove(i);
            } else {
                l.onNotificationListChanged(this, n, event);
            }
        }
    }

    /**
     * Returns {@code false} if the notification doesn't fit
     * the requirements (such as not ongoing and clearable).
     */
    private boolean isValidForLocal(@NonNull OpenNotification o) {
        AppConfig config = mBlacklist.getAppConfig(o.getPackageName());

        if (config.isHidden()) {
            // Do not display any notifications from this app.
            return false;
        }

        if (!o.isClearable() && !config.isNonClearableEnabled()) {
            // Do not display non-clearable notification.
            return false;
        }

        if (o.getNotification().priority < mConfig.getNotifyMinPriority()) {
            // Do not display low-priority notification.
            return false;
        }

        // Do not allow notifications without any content.
        NotificationData data = o.getNotificationData();
        return !(TextUtils.isEmpty(data.titleText)
                && TextUtils.isEmpty(data.titleBigText)
                && TextUtils.isEmpty(data.messageText)
                && TextUtils.isEmpty(data.infoText)
                && data.messageTextLines == null);
    }

    private boolean isValidForGlobal(@NonNull OpenNotification n) {
        return true;
    }

    // //////////////////////////////////////////
    // ///////// -- USER INTERFACE -- ///////////
    // //////////////////////////////////////////

    @SuppressLint("NewApi")
    private boolean isTestNotification(Context context, OpenNotification n) {
        StatusBarNotification sbn = n.getStatusBarNotification();
        return sbn != null
                && sbn.getId() == App.ID_NOTIFY_INIT
                && n.getPackageName().equals(PackageUtils.getName(context));
    }

}

