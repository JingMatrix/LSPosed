/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSPosed is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSPosed.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2022 LSPosed Contributors
 */

package org.lsposed.lspd.service;

import static org.lsposed.lspd.service.ServiceManager.TAG;

import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.ParceledListSlice;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import org.lsposed.daemon.BuildConfig;
import org.lsposed.daemon.R;
import org.lsposed.lspd.ICLIService;
import org.lsposed.lspd.models.Application;
import org.lsposed.lspd.util.FakeContext;
import org.lsposed.lspd.util.SignInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.time.LocalDateTime;

import io.github.libxposed.service.IXposedService;
import rikka.parcelablelist.ParcelableListSlice;

public class CLIService extends ICLIService.Stub {

    private final static Map<Integer, Session> sessions = new ConcurrentHashMap<>(3);
    private static final HandlerThread worker = new HandlerThread("cli worker");
    private static final Handler workerHandler;

    private static final String CHANNEL_ID = "lsposedpin";
    private static final String CHANNEL_NAME = "Pin code";
    private static final int CHANNEL_IMP = NotificationManager.IMPORTANCE_HIGH;
    private static final int NOTIFICATION_ID = 2000;
    private static final String opPkg = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ?
            "android" : "com.android.settings";

    // E/JavaBinder      ] *** Uncaught remote exception!  (Exceptions are not yet supported across processes.)
    private String sLastMsg;

    static {
        worker.start();
        workerHandler = new Handler(worker.getLooper());
    }

    CLIService() {
    }

    private int getParentPid(int pid) {
        try {
            Path path = Paths.get("/proc/" + pid + "/status");
            for (var sLine : Files.readAllLines(path)) {
                if (sLine.startsWith("PPid:")) {
                    return Integer.parseInt(sLine.split(":", 2)[1].trim());
                }
            }
        } catch (IOException io) {
            return -1;
        }
        return -1;
    }

    private static class Session {
        boolean bValid;
        String sPIN;
        LocalDateTime ldtStartSession;
    }

    public boolean isValidSession(int iPid, String sPin) {
        var iPPid = getParentPid(iPid);
        Log.d(TAG, "cli validating session pid=" + iPid + " ppid=" + iPPid);
        if (iPPid != -1) {
            int timeout = ConfigManager.getInstance().getSessionTimeout();
            if (timeout == -2) {
                return true;
            }
            Session session = sessions.get(iPPid);
            if (session != null) {
                if (!session.bValid) {
                    if (sPin != null && sPin.equals(session.sPIN)) {
                        session.bValid = true;
                        session.ldtStartSession = LocalDateTime.now();
                        Log.d(TAG, "cli valid session ppid=" + iPPid);
                        return true;
                    } else {
                        return false;
                    }
                }

                LocalDateTime ldtExpire = LocalDateTime.now().minusMinutes(timeout);

                if (session.ldtStartSession.isAfter(ldtExpire)) {
                    return true;
                } else {
                    sessions.remove(iPPid);
                }
            }
        }
        return false;
    }

    public void requestSession(int iPid) {
        var iPPid = getParentPid(iPid);
        Log.d(TAG, "cli request new session pid=" + iPid + " parent pid=" + iPPid);
        if (iPPid != -1) {
            Session session = new Session();
            session.sPIN = String.format("%06d", ThreadLocalRandom.current().nextInt(0, 999999));
            session.bValid = false;
            sessions.put(iPPid, session);
            showNotification(session.sPIN);
            Log.d(TAG, "cli request pin " + session.sPIN);
        }
    }

    private void showNotification(String sPin) {
        var context = new FakeContext();
        String title = context.getString(R.string.pin_request_notification_title);
        String content = sPin;

        var style = new Notification.BigTextStyle();
        style.bigText(content);

        var notification = new Notification.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setColor(Color.BLUE)
                .setSmallIcon(LSPNotificationManager.getNotificationIcon())
                .setAutoCancel(false)
                .setStyle(style)
                .build();
        notification.extras.putString("android.substName", "LSPosed");
        try {
            var nm = LSPNotificationManager.getNotificationManager();
            var list = new ArrayList<NotificationChannel>();

            final NotificationChannel channel =
                    new NotificationChannel(CHANNEL_ID, CHANNEL_NAME, CHANNEL_IMP);
            channel.setShowBadge(false);
            if (LSPNotificationManager.hasNotificationChannelForSystem(nm, CHANNEL_ID)) {
                nm.updateNotificationChannelForPackage("android", 1000, channel);
            } else {
                list.add(channel);
            }

            nm.createNotificationChannelsForPackage("android", 1000, new ParceledListSlice<>(list));
            nm.enqueueNotificationWithTag("android", opPkg, null, NOTIFICATION_ID, notification, 0);
        } catch (RemoteException e) {
            Log.e(TAG, "notifyStatusNotification: ", e);
        }
    }

    public static boolean basicCheck(int uid) {
        if (!ConfigManager.getInstance().isEnableCli()) {
            return false;
        }
        if (uid != 0) {
            return false;
        }
        return true;
    }

    public static boolean applicationStageNameValid(int pid, String processName) {
        var infoArr = processName.split(":");
        if (infoArr.length != 2 || !infoArr[0].equals("lsp-cli")) {
            return false;
        }

        if(infoArr[1].equals(SignInfo.CLI_UUID)) {
            return true;
        }
        return false;
    }

    private static boolean isValidXposedModule(String sPackageName) throws RemoteException {
        var appInfo = PackageService.getApplicationInfo(sPackageName, PackageManager.GET_META_DATA | PackageService.MATCH_ALL_FLAGS, 0);

        return appInfo != null && appInfo.metaData != null && appInfo.metaData.containsKey("xposedmodule");
    }

    @Override
    public IBinder asBinder() {
        return this;
    }

    @Override
    public int getXposedApiVersion() {
        return IXposedService.API;
    }

    @Override
    public int getXposedVersionCode() {
        return BuildConfig.VERSION_CODE;
    }

    @Override
    public String getXposedVersionName() {
        return BuildConfig.VERSION_NAME;
    }

    @Override
    public String getApi() {
        return ConfigManager.getInstance().getApi();
    }

    @Override
    public ParcelableListSlice<PackageInfo> getInstalledPackagesFromAllUsers(int flags, boolean filterNoProcess) throws RemoteException {
        return PackageService.getInstalledPackagesFromAllUsers(flags, filterNoProcess);
    }

    @Override
    public String[] enabledModules() {
        return ConfigManager.getInstance().enabledModules();
    }

    @Override
    public boolean enableModule(String packageName) throws RemoteException {
        if (!isValidXposedModule(packageName)) {
            sLastMsg = "Module " + packageName + " is not a valid xposed module";
            return false;
        }
        return ConfigManager.getInstance().enableModule(packageName);
    }

    @Override
    public boolean setModuleScope(String packageName, List<Application> scope) throws RemoteException {
        if (!isValidXposedModule(packageName)) {
            sLastMsg = "Module " + packageName + " is not a valid xposed module";
            return false;
        }
        return ConfigManager.getInstance().setModuleScope(packageName, scope);
    }

    @Override
    public List<Application> getModuleScope(String packageName) throws RemoteException {
        if (!isValidXposedModule(packageName)) {
            sLastMsg = "Module " + packageName + " is not a valid xposed module";
            return null;
        }
        List<Application> list = ConfigManager.getInstance().getModuleScope(packageName);
        if (list == null) return null;
        else return list;
    }

    @Override
    public boolean disableModule(String packageName) throws RemoteException {
        if (!isValidXposedModule(packageName)) {
            sLastMsg = "Module " + packageName + " is not a valid xposed module";
            return false;
        }
        return ConfigManager.getInstance().disableModule(packageName);
    }

    @Override
    public boolean isVerboseLog() {
        return ConfigManager.getInstance().verboseLog();
    }

    @Override
    public void setVerboseLog(boolean enabled) {
        ConfigManager.getInstance().setVerboseLog(enabled);
    }

    @Override
    public ParcelFileDescriptor getVerboseLog() {
        return ConfigManager.getInstance().getVerboseLog();
    }

    @Override
    public ParcelFileDescriptor getModulesLog() {
        workerHandler.post(() -> ServiceManager.getLogcatService().checkLogFile());
        return ConfigManager.getInstance().getModulesLog();
    }

    @Override
    public boolean clearLogs(boolean verbose) {
        return ConfigManager.getInstance().clearLogs(verbose);
    }

    @Override
    public void getLogs(ParcelFileDescriptor zipFd) throws RemoteException {
        ConfigFileManager.getLogs(zipFd);
    }

    @Override
    public String getLastErrorMsg() {
        return sLastMsg;
    }

    @Override
    public boolean getAutoInclude(String packageName) throws RemoteException {
        if (!isValidXposedModule(packageName)) {
            sLastMsg = "Module " + packageName + " is not a valid xposed module";
            return false;
        }
        return ConfigManager.getInstance().getAutoInclude(packageName);
    }

    @Override
    public void setAutoInclude(String packageName, boolean add) throws RemoteException {
        if (!isValidXposedModule(packageName)) {
            sLastMsg = "Module " + packageName + " is not a valid xposed module";
            return;
        }
        ConfigManager.getInstance().setAutoInclude(packageName, add);
    }
}
