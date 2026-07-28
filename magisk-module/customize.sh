#!/system/bin/sh

ui_print "- Installing AYANEO RGB UART access"
ui_print "- Pocket S2: /dev/ttyHS5"
ui_print "- Pocket EVO: /dev/ttyHS4"
ui_print "- Pocket FIT Elite: /dev/ttyHS1"
ui_print "- Unknown devices are left unchanged"
set_perm "$MODPATH/service.sh" 0 0 0755
