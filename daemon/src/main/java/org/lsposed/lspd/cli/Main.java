package org.lsposed.lspd.cli;

import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.lsposed.lspd.models.Application;
import org.lsposed.lspd.ICLIService;
import org.lsposed.lspd.service.ILSPApplicationService;
import org.lsposed.lspd.util.SignInfo;

import static org.lsposed.lspd.cli.Utils.CMDNAME;
import static org.lsposed.lspd.cli.Utils.ERRCODES;

import picocli.CommandLine;
import picocli.CommandLine.IExecutionExceptionHandler;
import picocli.CommandLine.IExitCodeExceptionMapper;
import picocli.CommandLine.ParseResult;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;

import java.time.LocalDateTime;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@CommandLine.Command(name = "log")
class LogCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private Main parent;

    @CommandLine.Option(names = {"-h", "--help", "help"}, usageHelp = true, description = "display this help message")
    boolean usageHelpRequested;
    @CommandLine.Option(names = {"-f", "--follow", "follow"}, description = "Follow update of log, as tail -f")
    boolean bFollow;
    @CommandLine.Option(names = {"-v", "--verbose", "verbose"}, description = "Get verbose log")
    boolean bVerboseLog;
    @CommandLine.Option(names = {"-c", "--clear", "clear"}, description = "Clear log")
    boolean bClear;

    @Override
    public Integer call() throws RemoteException {
        ICLIService manager = parent.getManager();
        if (bClear) {
            manager.clearLogs(bVerboseLog);
            if (!bFollow) { // we can clear old logs and follow new
                return 0;
            }
        }
        ParcelFileDescriptor pfdLog = bVerboseLog ? manager.getVerboseLog() : manager.getModulesLog();
        printLog(pfdLog);

        return ERRCODES.NOERROR.ordinal();
    }

    private void printLog(ParcelFileDescriptor parcelFileDescriptor) {
        var br = new BufferedReader(new InputStreamReader(new FileInputStream(parcelFileDescriptor.getFileDescriptor())));
        // TODO handle sigint when in follow mode for clean exit
        while (true) {
            String sLine;
            try {
                sLine = br.readLine();
            } catch (IOException ioe) { break; }
            if (sLine == null) {
                if (bFollow) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ie) {}
                } else {
                    break;
                }
            } else {
                System.out.println(sLine);
            }
        }
    }
}

@CommandLine.Command(name = "login",
        description = "Verifies the PIN and prints the shell command to set the session environment variable.")
class LoginCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private Main parent;

    @CommandLine.Option(names = "--for-eval",
            description = "Output only the export command for use with eval().")
    private boolean forEval;

    @Override
    public Integer call() throws Exception {
        // Step 1: Authenticate by requesting the manager.
        // If the PIN is wrong, parent.getManager() will throw a SecurityException,
        // which our main exception handler will catch and report to the user.
        // We don't need any try-catch block here.
        parent.getManager();

        // Step 2: If we reach here, authentication was successful.
        String pin = parent.pin;
        if (pin == null) {
            // This case should ideally not be hit if auth succeeded, but as a safeguard:
            System.err.println("Error: Could not retrieve the PIN used for authentication.");
            return 1;
        }

        String exportCommand = "export LSPOSED_CLI_PIN=\"" + pin + "\"";

        if (forEval) {
            // For power-users using `eval $(...)`
            System.out.println(exportCommand);
        } else {
            // For regular interactive users
            System.out.println("✅ Authentication successful.");
            System.out.println();
            System.out.println("To avoid typing the PIN for every command in this shell session, run the following command:");
            System.out.println();
            System.out.println("    " + exportCommand);
            System.out.println();
            System.out.println("You will then be able to run commands like 'lsposed-cli status' without the --pin argument.");
        }

        return 0; // Success
    }
}

@CommandLine.Command(name = "ls")
class ListModulesCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private ModulesCommand parent;

    @CommandLine.ArgGroup(exclusive = true)
    LSModuleOpts objArgs = new LSModuleOpts();

    static class LSModuleOpts {
        @CommandLine.Option(names = {"-e", "--enabled"}, description = "list only enabled modules", required = true)
        boolean bEnable;
        @CommandLine.Option(names = {"-d", "--disabled"}, description = "list only disabled modules", required = true)
        boolean bDisable;
    }

    private static final int MATCH_ANY_USER = 0x00400000; // PackageManager.MATCH_ANY_USER
    private static final int MATCH_ALL_FLAGS = PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.MATCH_DIRECT_BOOT_AWARE |
            PackageManager.MATCH_DIRECT_BOOT_UNAWARE | PackageManager.MATCH_UNINSTALLED_PACKAGES | MATCH_ANY_USER;

    @Override
    public Integer call() throws RemoteException {
        ICLIService manager = parent.parent.getManager();

        var lstEnabledModules = Arrays.asList(manager.enabledModules());
        var lstPackages = manager.getInstalledPackagesFromAllUsers(PackageManager.GET_META_DATA | MATCH_ALL_FLAGS, false);
        for (var packageInfo : lstPackages.getList()) {
            var metaData = packageInfo.applicationInfo.metaData;

            if (metaData != null && metaData.containsKey("xposedmodule")) {
                var bPkgEnabled = lstEnabledModules.contains(packageInfo.packageName);

                if ((objArgs.bEnable && bPkgEnabled) || (objArgs.bDisable && !bPkgEnabled) || (!objArgs.bEnable && !objArgs.bDisable)) {
                    var sFmt = "%-40s %10d %-8s";
                    System.out.println(String.format(sFmt, packageInfo.packageName, packageInfo.applicationInfo.uid, bPkgEnabled ? "enable" : "disable"));
                }
            }
        }

        return ERRCODES.NOERROR.ordinal();
    }
}

@CommandLine.Command(name = "revoke-pin", description = "Revokes the current CLI PIN, disabling CLI access.")
class RevokePinCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private Main parent;

    @Override
    public Integer call() throws Exception {
        System.out.println("Revoking current CLI PIN...");
        parent.getManager().revokeCurrentPin();
        System.out.println("PIN has been revoked. You must re-enable the CLI from the Manager app.");
        return 0;
    }
}

@CommandLine.Command(name = "set")
class SetModulesCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private ModulesCommand parent;

    @CommandLine.ArgGroup(exclusive = true, multiplicity = "1")
    SetModuleOpts objArgs = new SetModuleOpts();

    static class SetModuleOpts {
        @CommandLine.Option(names = {"-e", "--enable"}, description = "enable modules", required = true)
        boolean bEnable;
        @CommandLine.Option(names = {"-d", "--disable"}, description = "disable modules", required = true)
        boolean bDisable;
    }

    @CommandLine.Option(names = {"-i", "--ignore"}, description = "ignore not installed packages")
    boolean bIgnore;
    @CommandLine.Parameters(index = "0..*", description = "packages name", paramLabel="<modules name>", arity = "1")
    List<String> lstModules;

    @Override
    public Integer call() throws RemoteException {
        ICLIService manager = parent.parent.getManager();
        boolean bMsgReboot = false;

        for (var module : lstModules) {
            var lstScope = manager.getModuleScope(module);
            if (lstScope == null)
            {
                System.err.println(manager.getLastErrorMsg());
                continue;
            }
            if (objArgs.bEnable) {
                if (lstScope.size() < 2) {
                    System.err.println("Scope list is empty " + module + " not enabled");
                    return Utils.ERRCODES.EMPTY_SCOPE.ordinal();
                }
            }
            if (objArgs.bEnable) {
                if (!manager.enableModule(module)) {
                    System.err.println("Failed to enable");
                    return ERRCODES.ENABLE_DISABLE.ordinal();
                }
            } else {
                if (!manager.disableModule(module)) {
                    System.err.println("Failed to disable");
                    return ERRCODES.ENABLE_DISABLE.ordinal();
                }
            }
            if (Utils.checkPackageInScope("android", lstScope)) {
                bMsgReboot = true;
            }
        }
        if (bMsgReboot) {
            System.err.println("Reboot is required");
        }

        return ERRCODES.NOERROR.ordinal();
    }
}

@CommandLine.Command(name = "modules", subcommands = {ListModulesCommand.class, SetModulesCommand.class})
class ModulesCommand implements Runnable {
    @CommandLine.ParentCommand
    Main parent;

    @CommandLine.Option(names = {"-h", "--help", "help"}, usageHelp = true, description = "display this help message")
    boolean usageHelpRequested;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Missing required subcommand");
    }
}

class Scope extends Application {
    public static class Converter implements CommandLine.ITypeConverter<Scope> {
        @Override
        public Scope convert(String value) {
            var s = value.split("/", 2);
            return new Scope(s[0], Integer.parseInt(s[1]));
        }
    }

    public Scope(String packageName, int userId) {
        this.packageName = packageName;
        this.userId = userId;
    }

    @Override
    public String toString() {
        return "Scope{" +
                "packageName='" + packageName + '\'' +
                ", userId=" + userId +
                '}';
    }
}

@CommandLine.Command(name = "ls")
class ListScopeCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private ScopeCommand parent;

    @CommandLine.Parameters(index = "0", description = "module's name", paramLabel="<module name>")
    String moduleName;

    @Override
    public Integer call() throws RemoteException {
        ICLIService manager = parent.parent.getManager();

        var lstScope = manager.getModuleScope(moduleName);
        if (lstScope == null)
        {
            System.err.println(manager.getLastErrorMsg());
            return ERRCODES.LS_SCOPE.ordinal();
        }

        for (var scope : lstScope) {
            System.out.println(scope.packageName + "/" + scope.userId);
        }
        return ERRCODES.NOERROR.ordinal();
    }
}

@CommandLine.Command(name = "set", exitCodeOnExecutionException = 4 /* ERRCODES.SET_SCOPE */)
class SetScopeCommand implements Callable<Integer> {
    /*, multiplicity = "0..1"*/
    @CommandLine.ParentCommand
    private ScopeCommand parent;

    @CommandLine.ArgGroup(exclusive = true)
    ScopeOpts objArgs = new ScopeOpts();

    static class ScopeOpts {
        @CommandLine.Option(names = {"-s", "--set"}, description = "set a new scope (default)", required = true)
        boolean bSet;
        @CommandLine.Option(names = {"-a", "--append"}, description = "append packages to scope", required = true)
        boolean bAppend;
        @CommandLine.Option(names = {"-d", "--remove"}, description = "remove packages to scope", required = true)
        boolean bDel;
    }
    @CommandLine.Option(names = {"-i", "--ignore"}, description = "ignore not installed packages")
    boolean bIgnore;

    @CommandLine.Parameters(index = "0", description = "module's name", paramLabel="<module name>", arity = "1")
    String moduleName;

    @CommandLine.Parameters(index = "1..*", description = "package name/uid", arity = "1")
    Scope[] scopes;

    @Override
    public Integer call() throws RemoteException {
        boolean bMsgReboot = false;
        ICLIService manager = parent.parent.getManager();

        // default operation set
        // TODO find a mode for manage in picocli
        if (!objArgs.bSet && !objArgs.bAppend && !objArgs.bDel) {
            objArgs.bSet = true;
        }

        boolean bAndroidExist = false;
        if (objArgs.bSet) {
            var lstScope = manager.getModuleScope(moduleName);
            if (lstScope == null) {
                System.err.println(manager.getLastErrorMsg());
                return ERRCODES.SET_SCOPE.ordinal();
            }
            bAndroidExist = Utils.checkPackageInScope("android", lstScope);
        }

        for(var scope : scopes) {
            if (Utils.validPackageNameAndUserId(manager, scope.packageName, scope.userId)) {
                if (scope.packageName.equals("android")) {
                    bMsgReboot = true;
                }
            } else if (!bIgnore) {
                throw new RuntimeException("Error: " + scope.packageName + (scope.userId < 0? "" : ("/" + scope.userId)) + " is not a valid package name");
            }
        }
        if (bAndroidExist && !bMsgReboot) { // if android is removed with setcommand reboot is required
            bMsgReboot = true;
        }
        if (bMsgReboot) {
            System.err.println("Reboot is required");
        }
        if (objArgs.bSet) {
            List<Application> lstScope = new ArrayList<>(Arrays.asList(scopes)); // Arrays.asList return a read-only list and we require a changeable list
            if (Utils.checkPackageModule(moduleName, lstScope)) {
                System.err.println("Added package of module into scope!");
            }
            if (!manager.setModuleScope(moduleName, lstScope)) {
                throw new RuntimeException("Failed to set scope for " + moduleName);
            }
            if (lstScope.size() < 2) {
                manager.disableModule(moduleName);
            }
        } else {
            var lstScope = manager.getModuleScope(moduleName);
            if (lstScope == null) {
                System.err.println(manager.getLastErrorMsg());
                return ERRCODES.SET_SCOPE.ordinal();
            }
            for (var scope : scopes) {
                if (objArgs.bAppend) {
                    Application app = new Application();
                    app.packageName = scope.packageName;
                    app.userId = scope.userId;
                    lstScope.add(app);
                } else {
                    lstScope.removeIf(app -> scope.packageName.equals(app.packageName) && scope.userId == app.userId);
                }
            }
            if (Utils.checkPackageModule(moduleName, lstScope)) {
                System.err.println("Added package of module into scope!");
            }
            if (!manager.setModuleScope(moduleName, lstScope)) {
                throw new RuntimeException("Failed to set scope for " + moduleName);
            }
            if (lstScope.size() < 2) {
                manager.disableModule(moduleName);
            }
        }
        return ERRCODES.NOERROR.ordinal();
    }
}

@CommandLine.Command(name = "scope", subcommands = {ListScopeCommand.class, SetScopeCommand.class})
class ScopeCommand implements Runnable {
    @CommandLine.ParentCommand
    Main parent;

    @CommandLine.Option(names = {"-h", "--help", "help"}, usageHelp = true, description = "display this help message")
    boolean usageHelpRequested;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Missing required subcommand");
    }
}

@CommandLine.Command(name = "status")
class StatusCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private Main parent;

    @CommandLine.Option(names = {"-h", "--help", "help"}, usageHelp = true, description = "display this help message")
    boolean usageHelpRequested;

    @Override
    public Integer call() throws RemoteException {
        ICLIService manager = parent.getManager();
        String sSysVer;
        if (Build.VERSION.PREVIEW_SDK_INT != 0) {
            sSysVer = String.format("%1$s Preview (API %2$d)", Build.VERSION.CODENAME, Build.VERSION.SDK_INT);
        } else {
            sSysVer = String.format("%1$s (API %2$d)", Build.VERSION.RELEASE, Build.VERSION.SDK_INT);
        }

        var sPrint = "API version: " + manager.getXposedApiVersion() + '\n' +
                "Injection Interface: " + manager.getApi() + '\n' +
                "Framework version: " + manager.getXposedVersionName() + '(' + manager.getXposedVersionCode() + ")\n" +
                "System version: " + sSysVer + '\n' +
                "Device: " + getDevice() + '\n' +
                "System ABI: " + Build.SUPPORTED_ABIS[0];
        System.out.println(sPrint);
        return ERRCODES.NOERROR.ordinal();
    }

    private String getDevice() {
        String manufacturer = Character.toUpperCase(Build.MANUFACTURER.charAt(0)) + Build.MANUFACTURER.substring(1);
        if (!Build.BRAND.equals(Build.MANUFACTURER)) {
            manufacturer += " " + Character.toUpperCase(Build.BRAND.charAt(0)) + Build.BRAND.substring(1);
        }
        manufacturer += " " + Build.MODEL + " ";
        return manufacturer;
    }
}

@CommandLine.Command(name = "backup")
class BackupCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private Main parent;

    @CommandLine.Option(names = {"-h", "--help", "help"}, usageHelp = true, description = "display this help message")
    boolean usageHelpRequested;
    @CommandLine.Parameters(index = "0..*", description = "module's name default all", paramLabel="<module name>")
    String[] modulesName;
    @CommandLine.Option(names = {"-f", "--file"}, description = "output file")
    String file;

    private static final int VERSION = 2;
    private static final int MATCH_ANY_USER = 0x00400000; // PackageManager.MATCH_ANY_USER
    private static final int MATCH_ALL_FLAGS = PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.MATCH_DIRECT_BOOT_AWARE |
            PackageManager.MATCH_DIRECT_BOOT_UNAWARE | PackageManager.MATCH_UNINSTALLED_PACKAGES | MATCH_ANY_USER;

    @Override
    public Integer call() throws RemoteException {
        ICLIService manager = parent.getManager();

        if (modulesName == null) {
            List<String> modules = new ArrayList<>();
            var packages = manager.getInstalledPackagesFromAllUsers(PackageManager.GET_META_DATA | MATCH_ALL_FLAGS, false);
            for (var packageInfo : packages.getList()) {
                var metaData = packageInfo.applicationInfo.metaData;

                if (metaData != null && metaData.containsKey("xposedmodule")) {
                    modules.add(packageInfo.packageName);
                }
            }
            modulesName = modules.toArray(new String[0]);
        }
        if (file == null) {
            file = String.format("LSPosed_%s.lsp", LocalDateTime.now().toString());
        }

        var enabledModules = Arrays.asList(manager.enabledModules());
        JSONObject rootObject = new JSONObject();
        try {
            rootObject.put("version", VERSION);
            JSONArray modulesArray = new JSONArray();

            for (var module : modulesName) {
                JSONObject moduleObject = new JSONObject();
                moduleObject.put("enable", enabledModules.contains(module));
                moduleObject.put("package", module);
                moduleObject.put("autoInclude", manager.getAutoInclude(module));

                var scopes = manager.getModuleScope(module);
                JSONArray scopeArray = new JSONArray();
                for (var s : scopes) {
                    JSONObject app = new JSONObject();
                    app.put("package", s.packageName);
                    app.put("userId", s.userId);
                    scopeArray.put(app);
                }
                moduleObject.put("scope", scopeArray);
                modulesArray.put(moduleObject);
            }
            rootObject.put("modules", modulesArray);

            FileOutputStream fos = new FileOutputStream(file + ".gz");
            GZIPOutputStream gzipOutputStream = new GZIPOutputStream(fos);
            gzipOutputStream.write(rootObject.toString().getBytes());
            gzipOutputStream.close();
            fos.close();
        } catch(Exception ex) {
            throw new RemoteException(ex.getMessage());
        }
        return ERRCODES.NOERROR.ordinal();
    }
}

@CommandLine.Command(name = "restore")
class RestoreCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private Main parent;

    @CommandLine.Option(names = {"-h", "--help", "help"}, usageHelp = true, description = "display this help message")
    boolean usageHelpRequested;
    @CommandLine.Parameters(index = "0..*", description = "module's name default all", paramLabel="<module name>")
    String[] modulesName;
    @CommandLine.Option(names = {"-f", "--file"}, description = "input file", required = true)
    String file;

    private static final int VERSION = 2;
    private static final int MATCH_ANY_USER = 0x00400000; // PackageManager.MATCH_ANY_USER
    private static final int MATCH_ALL_FLAGS = PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.MATCH_DIRECT_BOOT_AWARE |
            PackageManager.MATCH_DIRECT_BOOT_UNAWARE | PackageManager.MATCH_UNINSTALLED_PACKAGES | MATCH_ANY_USER;

    @Override
    public Integer call() throws RemoteException {
        ICLIService manager = parent.getManager();

        StringBuilder json = new StringBuilder();
        try {
            FileInputStream fis = new FileInputStream(file);
            GZIPInputStream gzipInputStream = new GZIPInputStream(fis, 64);
            var os = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int length;
            while ((length = gzipInputStream.read(buf)) > 0) {
                os.write(buf, 0, length);
            }
            json.append(os);
            gzipInputStream.close();
            fis.close();
            os.close();
        } catch(Exception ex) {
            throw new RemoteException(ex.getMessage());
        }

        List<String> modules;
        if (modulesName == null) {
            modules = new ArrayList<>();
            var packages = manager.getInstalledPackagesFromAllUsers(PackageManager.GET_META_DATA | MATCH_ALL_FLAGS, false);
            for (var packageInfo : packages.getList()) {
                var metaData = packageInfo.applicationInfo.metaData;

                if (metaData != null && metaData.containsKey("xposedmodule")) {
                    modules.add(packageInfo.packageName);
                }
            }
        } else {
            modules = Arrays.asList(modulesName);
        }

        try {
            JSONObject rootObject = new JSONObject(json.toString());
            int version = rootObject.getInt("version");
            if (version == VERSION || version == 1) {
                JSONArray jsmodules = rootObject.getJSONArray("modules");
                int len = jsmodules.length();
                for (int i = 0; i < len; i++) {
                    JSONObject moduleObject = jsmodules.getJSONObject(i);
                    String name = moduleObject.getString("package");
                    if (!modules.contains(name)) {
                        continue;
                    }
                    var enabled = moduleObject.getBoolean("enable");
                    if (enabled) {
                        if (!manager.enableModule(name)) {
                            System.err.println(manager.getLastErrorMsg());
                            throw new RuntimeException("Failed to enable " + name);
                        }
                    } else {
                        if (!manager.disableModule(name)) {
                            System.err.println(manager.getLastErrorMsg());
                            throw new RuntimeException("Failed to disable " + name);
                        }
                    }
                    var autoInclude = false;
                    try {
                        autoInclude = moduleObject.getBoolean("autoInclude");
                    } catch (JSONException ignore) { }
                    manager.setAutoInclude(name, autoInclude);
                    JSONArray scopeArray = moduleObject.getJSONArray("scope");
                    List<Application> scopes = new ArrayList<>();
                    for (int j = 0; j < scopeArray.length(); j++) {
                        if (version == VERSION) {
                            JSONObject app = scopeArray.getJSONObject(j);
                            scopes.add(new Scope(app.getString("package"), app.getInt("userId")));
                        } else {
                            scopes.add(new Scope(scopeArray.getString(j), 0));
                        }
                    }
                    if (!manager.setModuleScope(name, scopes)) {
                        System.err.println(manager.getLastErrorMsg());
                        throw new RuntimeException("Failed to set scope for " + name);
                    }
                }
            } else {
                throw new RemoteException("Unknown backup file version");
            }
        }catch(JSONException je) {
            throw new RemoteException(je.getMessage());
        }

        return ERRCODES.NOERROR.ordinal();
    }
}

@CommandLine.Command(name = CMDNAME, subcommands = {LogCommand.class, LoginCommand.class, BackupCommand.class, ModulesCommand.class, RestoreCommand.class, ScopeCommand.class, StatusCommand.class, RevokePinCommand.class}, version = "0.3")
public class Main implements Runnable {
    @CommandLine.Option(names = {"-p", "--pin"}, description = "Authentication PIN for the CLI.", scope = CommandLine.ScopeType.INHERIT)
    String pin;

    @CommandLine.Option(names = {"-V", "--version", "version"}, versionHelp = true, description = "display version info")
    boolean versionInfoRequested;

    @CommandLine.Option(names = {"-h", "--help", "help"}, usageHelp = true, description = "display this help message")
    boolean usageHelpRequested;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    private static ICLIService objManager;

    public Main() {
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main())
            .setExecutionExceptionHandler((ex, commandLine, parseResult) -> {
                commandLine.getErr().println(ex.getMessage());
                return ex instanceof SecurityException ? ERRCODES.AUTH_FAILED.ordinal() : ERRCODES.REMOTE_ERROR.ordinal();
            })
            .execute(args));
    }

    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Missing required subcommand");
    }

    private static int exec(String[] args) {
        IExecutionExceptionHandler errorHandler = new IExecutionExceptionHandler() {
            public int handleExecutionException(Exception ex, CommandLine commandLine, ParseResult parseResult) {
                commandLine.getErr().println(ex.getMessage());
                if (ex instanceof RemoteException) {
                    return ERRCODES.REMOTE_ERROR.ordinal();
                }
                return commandLine.getCommandSpec().exitCodeOnExecutionException();
            }
        };
        int rc = new CommandLine(new Main())
                .registerConverter(Scope.class, new Scope.Converter())
                .setExecutionExceptionHandler(errorHandler)
                .execute(args);
        return rc;
    }

    public final ICLIService getManager() {
        if (objManager == null) {
            try {
                objManager = connectToService();
                if (objManager == null) {
                    // connectToService will throw, but as a fallback:
                    throw new SecurityException("Authentication failed or daemon service not available.");
                }
            } catch (RemoteException | SecurityException e) {
                System.err.println("Error: " + e.getMessage());
                System.exit(ERRCODES.NO_DAEMON.ordinal());
            }
        }
        return objManager;
    }

    private ICLIService connectToService() throws RemoteException {
        // 1. Check for credentials provided by the user via arguments or environment.
        // We store this in a separate variable to remember if the user even tried to provide a PIN.
        String initialPin = this.pin; // `this.pin` is populated by picocli from the --pin arg
        if (initialPin == null) {
            initialPin = System.getenv("LSPOSED_CLI_PIN");
        }
        // `this.pin` will be used for the actual connection attempts.
        this.pin = initialPin;

        // 2. Connect to the basic application service binder (boilerplate).
        var activityService = ServiceManager.getService("activity");
        if (activityService == null) throw new RemoteException("Could not get activity service.");

        var binder = new Binder();
        Parcel data = Parcel.obtain();
        data.writeInterfaceToken("LSPosed");
        data.writeInt(2);
        data.writeString("lsp-cli:" + org.lsposed.lspd.util.SignInfo.CLI_UUID);
        data.writeStrongBinder(binder);
        Parcel reply = Parcel.obtain();

        if (!activityService.transact(1598837584, data, reply, 0)) {
            throw new RemoteException("Transaction to activity service failed.");
        }

        reply.readException();
        var serviceBinder = reply.readStrongBinder();
        if (serviceBinder == null) throw new RemoteException("Daemon did not return a service binder.");

        var service = ILSPApplicationService.Stub.asInterface(serviceBinder);
        var lstBinder = new ArrayList<IBinder>(1);

        // 3. First attempt: Authenticate with the credentials we have (which could be null).
        service.requestCLIBinder(this.pin, lstBinder);

        // 4. Recovery step: If the first attempt failed, we have no PIN, AND we're in an
        //    interactive shell, then prompt the user as a last resort.
        if (lstBinder.isEmpty() && this.pin == null && System.console() != null) {
            System.err.println("Authentication required.");
            char[] pinChars = System.console().readPassword("Enter CLI PIN: ");
            if (pinChars != null) {
                this.pin = new String(pinChars);
                // Second attempt: Retry with the PIN from the interactive prompt.
                service.requestCLIBinder(this.pin, lstBinder);
            }
        }

        // 5. Final check and smart error reporting.
        if (lstBinder.isEmpty()) {
            String errorMessage;
            if (initialPin == null) {
                // The user never provided a PIN. The daemon requires one. Guide the user.
                errorMessage = "Authentication required. Use --pin, set LSPOSED_CLI_PIN, or use an interactive shell.";
            } else {
                // The user provided a PIN, but it was rejected by the daemon.
                errorMessage = "Authentication failed. The provided PIN is incorrect or CLI is disabled in the Manager app.";
            }
            throw new SecurityException(errorMessage);
        }

        // If we reach here, we are successful.
        return ICLIService.Stub.asInterface(lstBinder.get(0));
    }
}
