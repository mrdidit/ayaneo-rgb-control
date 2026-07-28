#!/system/bin/sh

DEVICE="$(getprop ro.product.device)"

case "$DEVICE" in
    PocketS2|PocketS2Pro)
        RGB_UART=/dev/ttyHS5
        ;;
    PocketEVO)
        RGB_UART=/dev/ttyHS4
        ;;
    PocketFITElite)
        RGB_UART=/dev/ttyHS1
        ;;
    *)
        # Unknown hardware: do not change permissions on any UART.
        exit 0
        ;;
esac

for attempt in 1 2 3 4 5 6 7 8 9 10; do
    if [ -e "$RGB_UART" ]; then
        chcon u:object_r:ayaneo_rgb_device:s0 "$RGB_UART"
        chown system:system "$RGB_UART"
        chmod 664 "$RGB_UART"
        exit 0
    fi
    sleep 1
done

exit 1
