package org.lsposed.lspd.cli;

import static org.lsposed.lspd.cli.Utils.CMDNAME;
import static org.lsposed.lspd.cli.Utils.ERRCODES;

import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceManager;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import org.lsposed.lspd.ICLIService;
import org.lsposed.lspd.models.Application;
import org.lsposed.lspd.service.ILSPApplicationService;

import picocli.CommandLine;
import picocli.CommandLine.IExecutionExceptionHandler;

/**
 * Main entry point for the LSPosed Command Line Interface (CLI).
 * <p>
 * This application uses the picocli framework to parse commands and arguments,
 * and communicates with the LSPosed daemon via Binder IPC to perform actions.
 */

//================================================================================
// Sub-Commands
//================================================================================

@CommandLine.Command(name = "ls", description = "Lists installed Xposed modules.")
class ListModulesCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private ModulesCommand parent;

    @CommandLine.Mixin
    private GlobalOptions globalOptions = new GlobalOptions();

    @CommandLine.ArgGroup(exclusive = true)
    LSModuleOpts objArgs = new LSModuleOpts();

    static class LSModuleOpts {
        @CommandLine.Option(names = {"-e", "--enabled"}, description = "List only modules that are currently enabled.", required = true)
        boolean bEnable;
        @CommandLine.Option(names = {"-d", "--disabled"}, description = "List only modules that are currently disabled.", required = true)
        boolean bDisable;
    }

    private static final int MATCH_ANY_USER = 0x00400000;
    private static final int MATCH_ALL_FLAGS = PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.MATCH_DIRECT_BOOT_AWARE |
            PackageManager.MATCH_DIRECT_BOOT_UNAWARE | PackageManager.MATCH_UNINSTALLED_PACKAGES | MATCH_ANY_USER;

    @Override
    public Integer call() throws RemoteException, JSONException {
        ICLIService manager = parent.parent.getManager();
        List<String> lstEnabledModules = Arrays.asList(manager.enabledModules());
        var lstPackages = manager.getInstalledPackagesFromAllUsers(PackageManager.GET_META_DATA | MATCH_ALL_FLAGS, false);
        JSONArray modulesArray = new JSONArray();
        boolean printedHeader = false;

        for (var packageInfo : lstPackages.getList()) {
            if (packageInfo.applicationInfo.metaData != null && packageInfo.applicationInfo.metaData.containsKey("xposedmodule")) {
                boolean isPkgEnabled = lstEnabledModules.contains(packageInfo.packageName);

                boolean shouldList = (!objArgs.bEnable && !objArgs.bDisable) || (objArgs.bEnable && isPkgEnabled) || (objArgs.bDisable && !isPkgEnabled);

                if (shouldList) {
                    if (globalOptions.jsonOutput) {
                        JSONObject moduleObject = new JSONObject();
                        moduleObject.put("packageName", packageInfo.packageName);
                        moduleObject.put("uid", packageInfo.applicationInfo.uid);
                        moduleObject.put("enabled", isPkgEnabled);
                        modulesArray.put(moduleObject);
                    } else {
                        if (!printedHeader) {
                            System.out.println(String.format("%-45s %-10s %-8s", "PACKAGE", "UID", "STATUS"));
                            printedHeader = true;
                        }
                        System.out.println(String.format("%-45s %-10d %-8s", packageInfo.packageName, packageInfo.applicationInfo.uid, isPkgEnabled ? "enabled" : "disabled"));
                    }
                }
            }
        }

        if (globalOptions.jsonOutput) {
            System.out.println(modulesArray.toString(2));
        }
        return ERRCODES.NOERROR.ordinal();
    }
}

@CommandLine.Command(name = "set", description = "Enables or disables one or more modules.")
class SetModulesCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private ModulesCommand parent;

    @CommandLine.Mixin
    private GlobalOptions globalOptions = new GlobalOptions();

    @CommandLine.ArgGroup(exclusive = true, multiplicity = "1")
    SetModuleOpts objArgs = new SetModuleOpts();

    static class SetModuleOpts {
        @CommandLine.Option(names = {"-e", "--enable"}, description = "Enable the specified modules.", required = true)
        boolean bEnable;
        @CommandLine.Option(names = {"-d", "--disable"}, description = "Disable the specified modules.", required = true)
        boolean bDisable;
    }

    @CommandLine.Option(names = {"-i", "--ignore"}, description = "Ignore modules that are not installed.")
    boolean bIgnore;
    @CommandLine.Parameters(index = "0..*", description = "The package name(s) of the module(s) to modify.", paramLabel = "<module_name...>", arity = "1..*")
    List<String> lstModules;

    @Override
    public Integer call() throws RemoteException, JSONException {
        ICLIService manager = parent.parent.getManager();
        boolean rebootRequired = false;
        boolean allSuccess = true;
        JSONArray resultsArray = new JSONArray();

        for (String module : lstModules) {
            String status = "unknown";
            String message;
            boolean success = false;
            String action = objArgs.bEnable ? "enable" : "disable";

            List<Application> scope = manager.getModuleScope(module);
            if (scope == null) {
                message = manager.getLastErrorMsg();
                allSuccess = false;
            } else if (objArgs.bEnable && scope.size() < 2) {
                message = "Cannot enable: module scope is empty.";
                allSuccess = false;
            } else {
                if (objArgs.bEnable ? manager.enableModule(module) : manager.disableModule(module)) {
                    success = true;
                    status = objArgs.bEnable ? "enabled" : "disabled";
                    message = "Module successfully " + status + ".";
                    if (Utils.checkPackageInScope("android", scope)) {
                        rebootRequired = true;
                    }
                } else {
                    message = "Failed to " + action + " module via daemon.";
                    allSuccess = false;
                }
            }

            if (globalOptions.jsonOutput) {
                JSONObject result = new JSONObject();
                result.put("module", module);
                result.put("success", success);
                result.put("status", status);
                result.put("message", message);
                resultsArray.put(result);
            } else {
                if (success) {
                    System.out.println(module + ": " + message);
                } else {
                    System.err.println(module + ": Error! " + message);
                }
            }
        }

        if (globalOptions.jsonOutput) {
            JSONObject finalOutput = new JSONObject();
            finalOutput.put("success", allSuccess);
            finalOutput.put("rebootRequired", rebootRequired);
            finalOutput.put("results", resultsArray);
            System.out.println(finalOutput.toString(2));
        } else if (rebootRequired) {
            System.err.println("\nWarning: A reboot is required for some changes to take full effect.");
        }

        return allSuccess ? ERRCODES.NOERROR.ordinal() : ERRCODES.ENABLE_DISABLE.ordinal();
    }
}

@CommandLine.Command(name = "modules", description = "Manages Xposed modules.", subcommands = {ListModulesCommand.class, SetModulesCommand.class})
class ModulesCommand implements Runnable {
    @CommandLine.ParentCommand
    Main parent;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Missing required subcommand. See 'lsposed-cli modules --help'.");
    }
}

class Scope extends Application {
    public static class Converter implements CommandLine.ITypeConverter<Scope> {
        @Override
        public Scope convert(String value) {
            String[] parts = value.split("/", 2);
            if (parts.length != 2) {
                throw new CommandLine.TypeConversionException("Invalid scope format. Expected 'package/user_id', but got '" + value + "'.");
            }
            try {
                return new Scope(parts[0], Integer.parseInt(parts[1]));
            } catch (NumberFormatException e) {
                throw new CommandLine.TypeConversionException("Invalid user_id in scope '" + value + "'. Must be an integer.");
            }
        }
    }

    public Scope(String packageName, int userId) {
        this.packageName = packageName;
        this.userId = userId;
    }
}

@CommandLine.Command(name = "ls", description = "Displays the scope of a specific module.")
class ListScopeCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private ScopeCommand parent;

    @CommandLine.Mixin
    private GlobalOptions globalOptions = new GlobalOptions();

    @CommandLine.Parameters(index = "0", description = "The package name of the module.", paramLabel = "<module_name>")
    String moduleName;

    @Override
    public Integer call() throws RemoteException, JSONException {
        ICLIService manager = parent.parent.getManager();
        List<Application> scopeList = manager.getModuleScope(moduleName);

        if (scopeList == null) {
            System.err.println("Error: " + manager.getLastErrorMsg());
            return ERRCODES.LS_SCOPE.ordinal();
        }

        if (globalOptions.jsonOutput) {
            JSONArray scopeArray = new JSONArray();
            for (Application app : scopeList) {
                scopeArray.put(app.packageName + "/" + app.userId);
            }
            System.out.println(scopeArray.toString(2));
        } else {
            for (Application app : scopeList) {
                System.out.println(app.packageName + "/" + app.userId);
            }
        }
        return ERRCODES.NOERROR.ordinal();
    }
}

@CommandLine.Command(name = "set", description = "Sets, appends to, or removes from a module's scope.")
class SetScopeCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private ScopeCommand parent;

    @CommandLine.Mixin
    private GlobalOptions globalOptions = new GlobalOptions();

    @CommandLine.ArgGroup(exclusive = true, multiplicity = "1")
    ScopeOpts objArgs = new ScopeOpts();

    static class ScopeOpts {
        @CommandLine.Option(names = {"-s", "--set"}, description = "Overwrite the entire scope with the given list.", required = true)
        boolean bSet;
        @CommandLine.Option(names = {"-a", "--append"}, description = "Add the given applications to the existing scope.", required = true)
        boolean bAppend;
        @CommandLine.Option(names = {"-d", "--remove"}, description = "Remove the given applications from the existing scope.", required = true)
        boolean bDel;
    }

    @CommandLine.Parameters(index = "0", description = "The package name of the module to configure.", paramLabel = "<module_name>", arity = "1")
    String moduleName;

    @CommandLine.Parameters(index = "1..*", description = "Application(s) to form the scope, as 'package/user_id'.", paramLabel = "<package/user...>", arity = "1..*")
    Scope[] scopes;

    @Override
    public Integer call() throws RemoteException, JSONException {
        ICLIService manager = parent.parent.getManager();
        boolean rebootRequired = false;

        for (Scope scope : scopes) {
            if (!parent.parent.getCliUtils().validPackageNameAndUserId(manager, scope.packageName, scope.userId)) {
                throw new RuntimeException("Error: Invalid application '" + scope.packageName + "/" + scope.userId + "'. Not an installed package for that user.");
            }
            if ("android".equals(scope.packageName)) {
                rebootRequired = true;
            }
        }

        List<Application> finalScope;
        if (objArgs.bSet) {
            finalScope = new ArrayList<>(Arrays.asList(scopes));
            List<Application> oldScope = manager.getModuleScope(moduleName);
            if (oldScope != null && Utils.checkPackageInScope("android", oldScope) && !rebootRequired) {
                rebootRequired = true; // Reboot is needed if 'android' is removed from scope.
            }
        } else {
            finalScope = manager.getModuleScope(moduleName);
            if (finalScope == null) {
                throw new RuntimeException("Error: " + manager.getLastErrorMsg());
            }
            for (Scope scope : scopes) {
                if (objArgs.bAppend) {
                    finalScope.add(scope);
                } else { // bDel
                    finalScope.removeIf(app -> scope.packageName.equals(app.packageName) && scope.userId == app.userId);
                }
            }
        }

        if (manager.setModuleScope(moduleName, finalScope)) {
            if (finalScope.size() < 2) {
                manager.disableModule(moduleName);
                if (!globalOptions.jsonOutput) {
                    System.err.println("Warning: Scope is now empty or contains only the module itself. Module has been disabled.");
                }
            }

            if (globalOptions.jsonOutput) {
                JSONObject result = new JSONObject();
                result.put("success", true);
                result.put("module", moduleName);
                result.put("rebootRequired", rebootRequired);
                System.out.println(result.toString(2));
            } else {
                System.out.println("Successfully updated scope for " + moduleName + ".");
                if (rebootRequired) {
                    System.err.println("Warning: A reboot is required for changes to take full effect.");
                }
            }
        } else {
            throw new RuntimeException("Failed to set scope for " + moduleName + ". Reason: " + manager.getLastErrorMsg());
        }
        return ERRCODES.NOERROR.ordinal();
    }
}

@CommandLine.Command(name = "scope", description = "Manages module scopes.", subcommands = {ListScopeCommand.class, SetScopeCommand.class})
class ScopeCommand implements Runnable {
    @CommandLine.ParentCommand
    Main parent;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Missing required subcommand. See 'lsposed-cli scope --help'.");
    }
}

@CommandLine.Command(name = "backup", description = "Creates a compressed backup of module settings and scopes.")
class BackupCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private Main parent;

    @CommandLine.Parameters(index = "0..*", description = "Specific module(s) to back up. If omitted, all modules are backed up.", paramLabel = "<module_name...>")
    String[] modulesName;
    @CommandLine.Option(names = {"-f", "--file"}, description = "Output file path. If omitted, a timestamped file is created in the current directory.", paramLabel = "<path>")
    String file;

    private static final int VERSION = 2;
    private static final int MATCH_ANY_USER = 0x00400000;
    private static final int MATCH_ALL_FLAGS = PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.MATCH_DIRECT_BOOT_AWARE |
            PackageManager.MATCH_DIRECT_BOOT_UNAWARE | PackageManager.MATCH_UNINSTALLED_PACKAGES | MATCH_ANY_USER;

    @Override
    public Integer call() throws RemoteException {
        ICLIService manager = parent.getManager();

        if (modulesName == null || modulesName.length == 0) {
            List<String> modules = new ArrayList<>();
            var packages = manager.getInstalledPackagesFromAllUsers(PackageManager.GET_META_DATA | MATCH_ALL_FLAGS, false);
            for (var packageInfo : packages.getList()) {
                if (packageInfo.applicationInfo.metaData != null && packageInfo.applicationInfo.metaData.containsKey("xposedmodule")) {
                    modules.add(packageInfo.packageName);
                }
            }
            modulesName = modules.toArray(new String[0]);
        }
        if (file == null) {
            file = String.format("LSPosed_%s.lsp.gz", LocalDateTime.now());
        }

        List<String> enabledModules = Arrays.asList(manager.enabledModules());
        JSONObject rootObject = new JSONObject();
        try {
            rootObject.put("version", VERSION);
            JSONArray modulesArray = new JSONArray();

            for (String module : modulesName) {
                JSONObject moduleObject = new JSONObject();
                moduleObject.put("enable", enabledModules.contains(module));
                moduleObject.put("package", module);
                moduleObject.put("autoInclude", manager.getAutoInclude(module));

                JSONArray scopeArray = new JSONArray();
                List<Application> scopes = manager.getModuleScope(module);
                if (scopes != null) {
                    for (Application s : scopes) {
                        JSONObject app = new JSONObject();
                        app.put("package", s.packageName);
                        app.put("userId", s.userId);
                        scopeArray.put(app);
                    }
                }
                moduleObject.put("scope", scopeArray);
                modulesArray.put(moduleObject);
            }
            rootObject.put("modules", modulesArray);

            try (FileOutputStream fos = new FileOutputStream(file);
                 GZIPOutputStream gzipOutputStream = new GZIPOutputStream(fos)) {
                gzipOutputStream.write(rootObject.toString().getBytes());
            }
            System.out.println("Backup created successfully at: " + file);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to create backup: " + ex.getMessage(), ex);
        }
        return ERRCODES.NOERROR.ordinal();
    }
}

@CommandLine.Command(name = "log", description = "Streams and manages the LSPosed framework and module logs.")
class LogCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private Main parent;

    @CommandLine.Option(names = {"-f", "--follow"}, description = "Continuously print new log entries as they appear.")
    boolean bFollow;
    @CommandLine.Option(names = {"-v", "--verbose"}, description = "Access the verbose framework logs instead of module logs.")
    boolean bVerboseLog;
    @CommandLine.Option(names = {"-c", "--clear"}, description = "Clear the specified log file before streaming.")
    boolean bClear;

    @Override
    public Integer call() throws RemoteException {
        ICLIService manager = parent.getManager();
        if (bClear) {
            manager.clearLogs(bVerboseLog);
            if (!bFollow) {
                return 0;
            }
        }
        ParcelFileDescriptor pfdLog = bVerboseLog ? manager.getVerboseLog() : manager.getModulesLog();
        printLog(pfdLog);

        return ERRCODES.NOERROR.ordinal();
    }

    private void printLog(ParcelFileDescriptor parcelFileDescriptor) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(parcelFileDescriptor.getFileDescriptor())))) {
            // TODO: Handle SIGINT for a graceful exit when in follow mode.
            while (true) {
                String sLine = br.readLine();
                if (sLine == null) {
                    if (bFollow) {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException ignored) {
                            break; // Exit loop on interrupt
                        }
                    } else {
                        break;
                    }
                } else {
                    System.out.println(sLine);
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading log file: " + e.getMessage());
        }
    }
}

@CommandLine.Command(name = "login", description = "Authenticates the CLI and provides a session variable for subsequent commands.")
class LoginCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private Main parent;

    @CommandLine.Option(names = "--for-eval", description = "Output only the export command for use with `eval $(...)`.")
    private boolean forEval;

    @Override
    public Integer call() throws Exception {
        // Authenticate by simply requesting the manager. An exception will be thrown on failure.
        parent.getManager();

        String pin = parent.pin;
        if (pin == null) {
            System.err.println("Error: Could not retrieve the PIN used for authentication.");
            return 1;
        }

        String exportCommand = "export LSPOSED_CLI_PIN=\"" + pin + "\"";

        if (forEval) {
            System.out.println(exportCommand);
        } else {
            System.out.println("✅ Authentication successful.");
            System.out.println("\nTo avoid typing the PIN for every command in this shell session, run:");
            System.out.println("\n    " + exportCommand + "\n");
            System.out.println("You can then run commands like 'lsposed-cli status' without the --pin argument.");
        }
        return 0;
    }
}

@CommandLine.Command(name = "status", description = "Displays the status and version of the LSPosed framework.")
class StatusCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private Main parent;

    @CommandLine.Mixin
    private GlobalOptions globalOptions = new GlobalOptions();

    @Override
    public Integer call() throws RemoteException, JSONException {
        ICLIService manager = parent.getManager();
        if (globalOptions.jsonOutput) {
            JSONObject status = new JSONObject();
            status.put("xposedApiVersion", manager.getXposedApiVersion());
            status.put("injectionInterface", manager.getApi());
            status.put("frameworkVersionName", manager.getXposedVersionName());
            status.put("frameworkVersionCode", manager.getXposedVersionCode());
            status.put("systemVersion", getSystemVersion());
            status.put("device", getDevice());
            status.put("systemAbi", Build.SUPPORTED_ABIS[0]);
            System.out.println(status.toString(2));
        } else {
            System.out.printf("API Version: %d\n", manager.getXposedApiVersion());
            System.out.printf("Injection Interface: %s\n", manager.getApi());
            System.out.printf("Framework Version: %s (%d)\n", manager.getXposedVersionName(), manager.getXposedVersionCode());
            System.out.printf("System Version: %s\n", getSystemVersion());
            System.out.printf("Device: %s\n", getDevice());
            System.out.printf("System ABI: %s\n", Build.SUPPORTED_ABIS[0]);
        }
        return ERRCODES.NOERROR.ordinal();
    }

    private String getSystemVersion() {
        if (Build.VERSION.PREVIEW_SDK_INT != 0) {
            return String.format("%s Preview (API %d)", Build.VERSION.CODENAME, Build.VERSION.SDK_INT);
        } else {
            return String.format("%s (API %d)", Build.VERSION.RELEASE, Build.VERSION.SDK_INT);
        }
    }

    private String getDevice() {
        String manufacturer = Character.toUpperCase(Build.MANUFACTURER.charAt(0)) + Build.MANUFACTURER.substring(1);
        if (!Build.BRAND.equals(Build.MANUFACTURER)) {
            manufacturer += " " + Character.toUpperCase(Build.BRAND.charAt(0)) + Build.BRAND.substring(1);
        }
        manufacturer += " " + Build.MODEL;
        return manufacturer;
    }
}

@CommandLine.Command(name = "restore", description = "Restores module settings and scopes from a backup file.")
class RestoreCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private Main parent;

    @CommandLine.Parameters(index = "0..*", description = "Specific module(s) to restore. If omitted, all modules in the backup are restored.", paramLabel = "<module_name...>")
    String[] modulesName;
    @CommandLine.Option(names = {"-f", "--file"}, description = "Path to the backup file to restore from.", required = true, paramLabel = "<path>")
    String file;

    private static final int VERSION = 2;

    @Override
    public Integer call() throws RemoteException {
        ICLIService manager = parent.getManager();

        String jsonString;
        try (FileInputStream fis = new FileInputStream(file);
             GZIPInputStream gzipInputStream = new GZIPInputStream(fis);
             ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int length;
            while ((length = gzipInputStream.read(buf)) > 0) {
                os.write(buf, 0, length);
            }
            jsonString = os.toString();
        } catch (Exception ex) {
            throw new RuntimeException("Failed to read backup file: " + ex.getMessage(), ex);
        }

        List<String> modulesToRestore = (modulesName == null || modulesName.length == 0) ? null : Arrays.asList(modulesName);

        try {
            JSONObject rootObject = new JSONObject(jsonString);
            int version = rootObject.getInt("version");
            if (version > VERSION) {
                throw new RuntimeException("Unsupported backup version: " + version);
            }

            JSONArray jsmodules = rootObject.getJSONArray("modules");
            for (int i = 0; i < jsmodules.length(); i++) {
                JSONObject moduleObject = jsmodules.getJSONObject(i);
                String name = moduleObject.getString("package");

                if (modulesToRestore != null && !modulesToRestore.contains(name)) {
                    continue; // Skip module if it's not in the user's specified list
                }

                System.out.println("Restoring settings for: " + name);

                if (moduleObject.getBoolean("enable")) {
                    manager.enableModule(name);
                } else {
                    manager.disableModule(name);
                }

                manager.setAutoInclude(name, moduleObject.optBoolean("autoInclude", false));

                JSONArray scopeArray = moduleObject.getJSONArray("scope");
                List<Application> scopes = new ArrayList<>();
                for (int j = 0; j < scopeArray.length(); j++) {
                    if (version == VERSION) {
                        JSONObject app = scopeArray.getJSONObject(j);
                        scopes.add(new Scope(app.getString("package"), app.getInt("userId")));
                    } else { // Legacy v1 format
                        scopes.add(new Scope(scopeArray.getString(j), 0));
                    }
                }
                manager.setModuleScope(name, scopes);
            }
            System.out.println("Restore completed successfully.");
        } catch (JSONException je) {
            throw new RuntimeException("Failed to parse backup file: " + je.getMessage(), je);
        }
        return ERRCODES.NOERROR.ordinal();
    }
}

@CommandLine.Command(name = "revoke-pin", description = "Revokes the current CLI PIN. Disables CLI access until re-enabled from the Manager app.")
class RevokePinCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private Main parent;

    @CommandLine.Mixin
    private GlobalOptions globalOptions = new GlobalOptions();

    @Override
    public Integer call() throws Exception {
        parent.getManager().revokeCurrentPin();
        if (globalOptions.jsonOutput) {
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("message", "PIN has been revoked. Re-enable the CLI from the Manager app to generate a new one.");
            System.out.println(result.toString(2));
        } else {
            System.out.println("✅ PIN has been revoked. You must re-enable the CLI from the Manager app to generate a new one.");
        }
        return 0;
    }
}

//================================================================================
// Main Application Class
//================================================================================

@CommandLine.Command(name = CMDNAME,
        version = "LSPosed CLI 0.4",
        mixinStandardHelpOptions = true, // Use picocli's built-in --help and --version
        header = "LSPosed Command Line Interface",
        description = "A tool to manage the LSPosed framework and modules from the command line.",
        subcommands = {
                ModulesCommand.class,
                ScopeCommand.class,
                BackupCommand.class,
                LogCommand.class,
                LoginCommand.class,
                StatusCommand.class,
                RestoreCommand.class,
                RevokePinCommand.class
        })
public class Main implements Runnable {

    @CommandLine.Option(names = {"-p", "--pin"}, description = "Authentication PIN for the CLI.", scope = CommandLine.ScopeType.INHERIT)
    String pin;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    private ICLIService objManager;
    private final Utils cliUtils;

    public Main() {
        this.cliUtils = new Utils();
    }

    public Utils getCliUtils() {
        return this.cliUtils;
    }

    /**
     * The main entry point for the CLI application.
     * This method sets up picocli and includes robust, two-level error handling.
     */
    public static void main(String[] args) {
        try {
            // Level 1: Handles errors during command execution (inside a command's call() method).
            IExecutionExceptionHandler executionErrorHandler = (ex, commandLine, parseResult) -> {
                commandLine.getErr().println("Error: " + ex.getMessage());
                // For debug purposes, uncomment the next line to see the full stack trace.
                // ex.printStackTrace(commandLine.getErr());
                return ex instanceof SecurityException ? ERRCODES.AUTH_FAILED.ordinal() : ERRCODES.REMOTE_ERROR.ordinal();
            };

            int exitCode = new CommandLine(new Main())
                    .registerConverter(Scope.class, new Scope.Converter())
                    .setExecutionExceptionHandler(executionErrorHandler)
                    .execute(args);

            System.exit(exitCode);

        } catch (Exception e) {
            // Level 2: Catches errors during picocli initialization (e.g., parsing annotations).
            // This is crucial for debugging the command structure itself.
            System.err.println("A fatal initialization error occurred:");
            e.printStackTrace(System.err);
            System.exit(ERRCODES.REMOTE_ERROR.ordinal());
        }
    }

    @Override
    public void run() {
        // This is triggered if the user runs `lsposed-cli` with no subcommand.
        throw new CommandLine.ParameterException(spec.commandLine(), "Missing required subcommand. Use '--help' to see available commands.");
    }

    /**
     * Gets or creates a connection to the LSPosed daemon service.
     * This method caches the connection for the lifetime of the command.
     */
    public final ICLIService getManager() {
        if (objManager == null) {
            try {
                objManager = connectToService();
                if (objManager == null) {
                    throw new SecurityException("Authentication failed or daemon service not available.");
                }
            } catch (RemoteException | SecurityException e) {
                System.err.println("Error: " + e.getMessage());
                System.exit(ERRCODES.NO_DAEMON.ordinal());
            }
        }
        return objManager;
    }

    /**
     * Establishes a secure Binder connection to the LSPosed daemon.
     * This method handles PIN retrieval, interactive prompts, and authentication.
     */
    private ICLIService connectToService() throws RemoteException {
        // Step 1: Determine the PIN provided by the user via argument or environment variable.
        String initialPin = this.pin;
        if (initialPin == null) {
            initialPin = System.getenv("LSPOSED_CLI_PIN");
        }
        this.pin = initialPin; // This instance variable will hold the PIN used for the actual attempt.

        // Step 2: Connect to the 'activity' service to request the LSPosed application service binder.
        IBinder activityService = ServiceManager.getService("activity");
        if (activityService == null) throw new RemoteException("Could not get activity service.");

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        ILSPApplicationService service;
        try {
            data.writeInterfaceToken("LSPosed");
            data.writeInt(2);
            data.writeString("lsp-cli:" + org.lsposed.lspd.util.SignInfo.CLI_UUID);
            data.writeStrongBinder(new Binder());

            if (!activityService.transact(1598837584, data, reply, 0)) {
                throw new RemoteException("Transaction to activity service failed.");
            }
            reply.readException();
            IBinder serviceBinder = reply.readStrongBinder();
            if (serviceBinder == null) throw new RemoteException("Daemon did not return a service binder.");
            service = ILSPApplicationService.Stub.asInterface(serviceBinder);
        } finally {
            data.recycle();
            reply.recycle();
        }

        // Step 3: First authentication attempt with the provided PIN (which could be null).
        List<IBinder> lstBinder = new ArrayList<>(1);
        service.requestCLIBinder(this.pin, lstBinder);

        // Step 4: If the first attempt failed and no PIN was provided in an interactive shell,
        // prompt the user for the PIN as a final recovery step.
        if (lstBinder.isEmpty() && this.pin == null && System.console() != null) {
            System.err.println("Authentication required.");
            char[] pinChars = System.console().readPassword("Enter CLI PIN: ");
            if (pinChars != null) {
                this.pin = new String(pinChars);
                Arrays.fill(pinChars, ' '); // Clear the PIN from memory
                service.requestCLIBinder(this.pin, lstBinder); // Second authentication attempt.
            }
        }

        // Step 5: Final validation and user-friendly error reporting.
        if (lstBinder.isEmpty()) {
            String errorMessage = (initialPin == null)
                    ? "Authentication required. Use the --pin option, set the LSPOSED_CLI_PIN environment variable, or use an interactive shell."
                    : "Authentication failed. The provided PIN is incorrect, has been revoked, or the CLI is disabled in the Manager app.";
            throw new SecurityException(errorMessage);
        }

        return ICLIService.Stub.asInterface(lstBinder.get(0));
    }
}
