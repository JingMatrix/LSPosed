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

    private static final HandlerThread worker = new HandlerThread("cli worker");
    private static final Handler workerHandler;

    private String sLastMsg;

    static {
        worker.start();
        workerHandler = new Handler(worker.getLooper());
    }

    CLIService() {
    }

    @Override
    public void revokeCurrentPin() {
        ConfigManager.getInstance().disableCli();
    }

    public static boolean basicCheck(int uid) {
        return uid == 0;
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
