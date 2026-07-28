#!/system/bin/sh

ui_print "- Installing AYANEO RGB UART access"
ui_print "- Target: /dev/ttyHS5 only"
set_perm "$MODPATH/service.sh" 0 0 0755
