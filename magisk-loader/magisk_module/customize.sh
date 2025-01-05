#
# This file is part of LSPosed.
#
# LSPosed is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# LSPosed is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with LSPosed.  If not, see <https://www.gnu.org/licenses/>.
#
# Copyright (C) 2020 EdXposed Contributors
# Copyright (C) 2021 LSPosed Contributors
#

# shellcheck disable=SC2034
SKIPUNZIP=1

FLAVOR=@FLAVOR@

enforce_install_from_magisk_app() {
  if $BOOTMODE; then
    ui_print "- Installing from Magisk app"
  else
    ui_print "*********************************************************"
    ui_print "! Install from recovery is NOT supported"
    ui_print "! Some recovery has broken implementations, please thus install from Magisk app"
    abort "*********************************************************"
  fi
}

VERSION=$(grep_prop version "${TMPDIR}/module.prop")
ui_print "- LSPosed version ${VERSION}"

# Extract verify.sh
ui_print "- Extracting verify.sh"
unzip -o "$ZIPFILE" 'verify.sh' -d "$TMPDIR" >&2
if [ ! -f "$TMPDIR/verify.sh" ]; then
  ui_print "*********************************************************"
  ui_print "! Unable to extract verify.sh!"
  ui_print "! This zip may be corrupted, please try downloading again"
  abort    "*********************************************************"
fi
. "$TMPDIR/verify.sh"

# Base check
extract "$ZIPFILE" 'customize.sh' "$TMPDIR"
extract "$ZIPFILE" 'verify.sh' "$TMPDIR"
extract "$ZIPFILE" 'util_functions.sh' "$TMPDIR"
. "$TMPDIR/util_functions.sh"
check_android_version
if [ -z "$KSU" ] && [ -z "$APATCH" ]; then
  check_magisk_version
fi
check_incompatible_module

enforce_install_from_magisk_app

# Check architecture
if [ "$ARCH" != "arm" ] && [ "$ARCH" != "arm64" ] && [ "$ARCH" != "x86" ] && [ "$ARCH" != "x64" ]; then
  abort "! Unsupported platform: $ARCH"
else
  ui_print "- Device platform: $ARCH"
fi

# Extract libs
ui_print "- Extracting module files"

extract "$ZIPFILE" 'module.prop'        "$MODPATH"
extract "$ZIPFILE" 'action.sh'          "$MODPATH"
extract "$ZIPFILE" 'post-fs-data.sh'    "$MODPATH"
extract "$ZIPFILE" 'service.sh'         "$MODPATH"
extract "$ZIPFILE" 'uninstall.sh'       "$MODPATH"
extract "$ZIPFILE" 'sepolicy.rule'      "$MODPATH"
extract "$ZIPFILE" 'framework/lspd.dex' "$MODPATH"
extract "$ZIPFILE" 'daemon.apk'         "$MODPATH"
extract "$ZIPFILE" 'daemon'             "$MODPATH"
rm -f /data/adb/lspd/manager.apk
extract "$ZIPFILE" 'manager.apk'        "$MODPATH"

if [ "$FLAVOR" == "zygisk" ]; then
  # extract for KernelSU and APatch
  if [ "$KSU" ] || [ "$APATCH" ]; then
    # webroot only for zygisk
    mkdir -p "$MODPATH/webroot"
    extract "$ZIPFILE" "webroot/index.html" "$MODPATH/webroot" true
    # evaluate if use awk or tr -s ' ' | cut -d' ' -f5
    SRCJS=$(unzip -l "$ZIPFILE" | grep "webroot/src" | grep -v sha256 | awk '{print $4}')
    extract "$ZIPFILE" "$SRCJS" "$MODPATH/webroot" true
  fi

  mkdir -p "$MODPATH/zygisk"

  if [ "$ARCH" = "arm" ] || [ "$ARCH" = "arm64" ]; then
    extract "$ZIPFILE" "lib/armeabi-v7a/liblspd.so" "$MODPATH/zygisk" true
    mv "$MODPATH/zygisk/liblspd.so" "$MODPATH/zygisk/armeabi-v7a.so"

    if [ "$IS64BIT" = true ]; then
      extract "$ZIPFILE" "lib/arm64-v8a/liblspd.so" "$MODPATH/zygisk" true
      mv "$MODPATH/zygisk/liblspd.so" "$MODPATH/zygisk/arm64-v8a.so"
    fi
  fi

  if [ "$ARCH" = "x86" ] || [ "$ARCH" = "x64" ]; then
    extract "$ZIPFILE" "lib/x86/liblspd.so" "$MODPATH/zygisk" true
    mv "$MODPATH/zygisk/liblspd.so" "$MODPATH/zygisk/x86.so"

    if [ "$IS64BIT" = true ]; then
      extract "$ZIPFILE" "lib/x86_64/liblspd.so" "$MODPATH/zygisk" true
      mv "$MODPATH/zygisk/liblspd.so" "$MODPATH/zygisk/x86_64.so"
    fi
  fi
fi

if [ "$API" -ge 29 ]; then
  ui_print "- Extracting dex2oat binaries"
  mkdir "$MODPATH/bin"

  if [ "$ARCH" = "arm" ] || [ "$ARCH" = "arm64" ]; then
    extract "$ZIPFILE" "bin/armeabi-v7a/dex2oat" "$MODPATH/bin" true
    extract "$ZIPFILE" "bin/armeabi-v7a/liboat_hook.so" "$MODPATH/bin" true
    mv "$MODPATH/bin/dex2oat" "$MODPATH/bin/dex2oat32"
    mv "$MODPATH/bin/liboat_hook.so" "$MODPATH/bin/liboat_hook32.so"

    if [ "$IS64BIT" = true ]; then
      extract "$ZIPFILE" "bin/arm64-v8a/dex2oat" "$MODPATH/bin" true
      extract "$ZIPFILE" "bin/arm64-v8a/liboat_hook.so" "$MODPATH/bin" true
      mv "$MODPATH/bin/dex2oat" "$MODPATH/bin/dex2oat64"
      mv "$MODPATH/bin/liboat_hook.so" "$MODPATH/bin/liboat_hook64.so"
    fi
  elif [ "$ARCH" == "x86" ] || [ "$ARCH" == "x64" ]; then
    extract "$ZIPFILE" "bin/x86/dex2oat" "$MODPATH/bin" true
    extract "$ZIPFILE" "bin/x86/liboat_hook.so" "$MODPATH/bin" true
    mv "$MODPATH/bin/dex2oat" "$MODPATH/bin/dex2oat32"
    mv "$MODPATH/bin/liboat_hook.so" "$MODPATH/bin/liboat_hook32.so"

    if [ "$IS64BIT" = true ]; then
      extract "$ZIPFILE" "bin/x86_64/dex2oat" "$MODPATH/bin" true
      extract "$ZIPFILE" "bin/x86_64/liboat_hook.so" "$MODPATH/bin" true
      mv "$MODPATH/bin/dex2oat" "$MODPATH/bin/dex2oat64"
      mv "$MODPATH/bin/liboat_hook.so" "$MODPATH/bin/liboat_hook64.so"
    fi
  fi

  ui_print "- Patching binaries"
  DEV_PATH=$(tr -dc 'a-z0-9' < /dev/urandom | head -c 32)
  sed -i "s/5291374ceda0aef7c5d86cd2a4f6a3ac/$DEV_PATH/g" "$MODPATH/daemon.apk"
  sed -i "s/5291374ceda0aef7c5d86cd2a4f6a3ac/$DEV_PATH/" "$MODPATH/bin/dex2oat32"
  sed -i "s/5291374ceda0aef7c5d86cd2a4f6a3ac/$DEV_PATH/" "$MODPATH/bin/dex2oat64"
else
  extract "$ZIPFILE" 'system.prop' "$MODPATH"
fi

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm_recursive "$MODPATH/bin" 0 2000 0755 0755 u:object_r:xposed_file:s0
chmod 0744 "$MODPATH/daemon"

if [ "$(grep_prop ro.maple.enable)" == "1" ] && [ "$FLAVOR" == "zygisk" ]; then
  ui_print "- Add ro.maple.enable=0"
  echo "ro.maple.enable=0" >> "$MODPATH/system.prop"
fi

ui_print "- Welcome to LSPosed!"
