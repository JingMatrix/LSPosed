# How to troubleshoot LSPosed?

When LSPosed doesn't work as expected on your device, don't panic.
The following guide should help you out for most cases.

## Before we start the troubleshooting

1. Please flash the debug version of the latest CI build in [GitHub Actions](https://github.com/JingMatrix/LSPosed/actions), which could have fixed the problem for you. Moreover, debug logs are necessary for maintainers to provide their helps. However, make sure that you pick up builds from the `master` branch instead of some working-in-progress pull-requests.
2. For non-Magisk users, please flash the latest Zygisk module (debug version if possible), such as [ReZygisk](https://github.com/PerformanC/ReZygisk) or [ZygiskNext](https://github.com/Dr-TSNG/ZygiskNext/releases). The author [JingMatrix](https://github.com/JingMatrix) also contributes to ReZygisk.

##  Status notification for LSPosed manger is not shown

Update your root manager and use the `Action` button to open LSPosed manger

## LSPosed manger cannot be opened

1. Grant root permission to the system `Shell`(com.android.shell) application.
2. Install LSPosed manger as a user application using the following command:
```
adb shell su -c cp /data/adb/modules/zygisk_lsposed/manager.apk /data/local/tmp && adb pull /data/local/tmp/manager.apk && adb install ./manager.apk
```
3. Open manager as a normal application from your launcher.

## How to provide useful logs before asking helps?

1. Please go through the previous instructions to open LSPosed manager successfully, and upload the generated logs in your issue.
2. LSPosed manager will automatically save logs of the previous user session. Taking good advantage of the feature can help maintainers to find out a specific bug introduced in some single commit.
3. In case that the LSPosed manager still cannot be opened, we need to the following logs (file `lsposed.log`) from `adb logcat`:
```
adb logcat -s Magisk lspd nativeloader AndroidRuntime LSPosed  LSPosed-Bridge LSPlant LSPosedContext zygisk64 LSPlt Dobby LSPosedService '*:F' > lsposed.log
```
4. The above `adb logcat` command is meant to capture the booting logs of LSPosed. Hence, one should connect the device with computer, authorize the `adb` connection, run `adb reboot`, and finally run the previous `adb logcat` command once the phone screen turns on.


Notes for Chinese speakers: 本项目只接受英语**标题**的issue。如果您不懂英语，请使用[翻译工具](https://www.deepl.com/zh/translator).
