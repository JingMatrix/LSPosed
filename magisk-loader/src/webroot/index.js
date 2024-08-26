import { exec } from "kernelsu";
import { toast } from "kernelsu";

async function open_manager() {
  toast("LSPosed Manager starting...");
  const { errno, stdout, stderr } = await exec(
    "am start -c org.lsposed.manager.LAUNCH_MANAGER com.android.shell/.BugreportWarningActivity",
    { cwd: "/system" }
  );
}

open_manager();
