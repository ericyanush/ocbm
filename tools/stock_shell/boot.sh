#!/bin/sh
# OCBM stock-shell bootstrap: NCM + root telnet, before the vendor claims the gadget.
# This script lives on flash; runtime flags, DHCP state and logs live in /tmp.
(
  i=0
  until grep -q ' /tmp ' /proc/mounts 2>/dev/null || [ "$i" -ge 100 ]; do
    i=$((i + 1)); sleep 0.1
  done
  touch /tmp/UDiskPassThroughMode

  tar -xf /script/ko.tar.gz -C /tmp 2>/dev/null
  grep -q g_android_accessory /proc/modules || {
    insmod /tmp/storage_common.ko 2>/dev/null
    insmod /tmp/g_android_accessory.ko 2>/dev/null
  }

  A=/sys/class/android_usb_accessory/android0
  i=0
  while [ ! -e "$A/enable" ] && [ "$i" -lt 50 ]; do
    i=$((i + 1)); sleep 0.1
  done
  [ -e "$A/enable" ] || exit 0

  echo 0 > "$A/enable"
  printf 'OCBM root shell' > "$A/iProduct"
  echo 239 > "$A/bDeviceClass"
  echo 2 > "$A/bDeviceSubClass"
  echo 1 > "$A/bDeviceProtocol"
  echo ncm > "$A/functions"
  echo 1 > "$A/enable"
  i=0
  until [ -e /sys/class/net/ncm0 ] || [ "$i" -ge 20 ]; do
    i=$((i + 1)); sleep 0.1
  done

  if [ -e /sys/class/net/ncm0 ]; then
    pkill -f start_main_service.sh
    killall ARMadb-driver 2>/dev/null
    ifconfig ncm0 hw ether c2:8e:30:53:48:01
    ifconfig ncm0 192.168.50.2 netmask 255.255.255.0 mtu 1500 up
    printf 'start 192.168.50.100\nend 192.168.50.150\ninterface ncm0\nopt subnet 255.255.255.0\nopt lease 86400\nlease_file /tmp/ocbm-stock-udhcpd.leases\npidfile /tmp/ocbm-stock-udhcpd.pid\nmax_leases 20\n' > /tmp/ocbm-stock-udhcpd.conf
    touch /tmp/ocbm-stock-udhcpd.leases
    busybox udhcpd /tmp/ocbm-stock-udhcpd.conf
  fi
  busybox telnetd -l /bin/sh -p 23
) >/tmp/ocbm_stock_shell.log 2>&1 &
