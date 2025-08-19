package org.lsposed.lspd;

import rikka.parcelablelist.ParcelableListSlice;
import org.lsposed.lspd.models.Application;

interface ICLIService {
    String getApi() = 1;

    ParcelableListSlice<PackageInfo> getInstalledPackagesFromAllUsers(int flags, boolean filterNoProcess) = 2;

    String[] enabledModules() = 3;

    boolean enableModule(String packageName) = 4;

    boolean disableModule(String packageName) = 5;

    boolean setModuleScope(String packageName, in List<Application> scope) = 6;

    List<Application> getModuleScope(String packageName) = 7;

    boolean isVerboseLog() = 8;

    void setVerboseLog(boolean enabled) = 9;

    ParcelFileDescriptor getVerboseLog() = 10;

    ParcelFileDescriptor getModulesLog() = 11;

    int getXposedVersionCode() = 12;

    String getXposedVersionName() = 13;

    int getXposedApiVersion() = 14;

    boolean clearLogs(boolean verbose) = 15;

    void getLogs(in ParcelFileDescriptor zipFd) = 16;

    String getLastErrorMsg() = 17;

    boolean getAutoInclude(String packageName) = 18;

    void setAutoInclude(String packageName, boolean add) = 19;
}
