#!/bin/sh
set -eu

HOSTNAME="$1"

mkdir -p etc
printf '%s\n' "$HOSTNAME" > etc/hostname

cat > etc/inittab <<'EOF'
::sysinit:/sbin/openrc sysinit
::wait:/sbin/openrc boot
::ctrlaltdel:/sbin/reboot
::shutdown:/sbin/openrc shutdown

ttyS0::respawn:/sbin/getty -L ttyS0 115200 vt100
EOF
