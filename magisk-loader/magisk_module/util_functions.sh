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

check_magisk_version() {
  ui_print "- Magisk version: $MAGISK_VER_CODE"
  if [ "$FLAVOR" == "riru" ] || [ "$FLAVOR" == "zygisk" ]; then
    if [ "$MAGISK_VER_CODE" -lt 26000 ]; then
      ui_print "*********************************************************"
      ui_print "! Please install Magisk v26+"
      abort    "*********************************************************"
    fi
  else
    ui_print "*********************************************************"
    ui_print "! Unsupported flavor $FLAVOR"
    abort    "*********************************************************"
  fi
}

require_new_android() {
  ui_print "*********************************************************"
  ui_print "! Unsupported Android version ${1} (below ${2})"
  ui_print "! Learn more from our GitHub"
  [ "$BOOTMODE" == "true" ] && am start -a android.intent.action.VIEW -d https://github.com/JingMatrix/LSPosed/#supported-versions
  abort    "*********************************************************"
}

check_android_version() {
  local required_version
  local min_api

  if [ "$ARCH" == "riscv64" ]; then
    required_version="Vanilla Ice Cream"
    min_api=35
  else
    required_version="Oreo MR1"
    min_api=27
  fi

  if [ "$API" -ge "$min_api" ]; then
    ui_print "- Android SDK version: $API"
  else
    require_new_android "$API" "$required_version"
  fi
}

check_incompatible_module() {
  MODULEDIR="$(magisk --path)/.magisk/modules"
  for id in "riru_dreamland" "riru_edxposed" "riru_edxposed_sandhook" "taichi"; do
    if [ -d "$MODULEDIR/$id" ] && [ ! -f "$MODULEDIR/$id/disable" ] && [ ! -f "$MODULEDIR/$id/remove" ]; then
      ui_print "*********************************************************"
      ui_print "! Please disable or uninstall incompatible frameworks:"
      ui_print "! $id"
      abort    "*********************************************************"
      break
    fi
  done
}
