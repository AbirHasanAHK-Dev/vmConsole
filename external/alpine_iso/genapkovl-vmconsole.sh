#!/bin/sh
set -eu

HOSTNAME="$1"
ROOT="$(mktemp -d)"
cleanup() {
    rm -rf "$ROOT"
}
trap cleanup EXIT INT TERM

mkdir -p "$ROOT/etc" "$ROOT/usr/local/sbin" "$ROOT/etc/profile.d" "$ROOT/etc/network" "$ROOT/etc/local.d" "$ROOT/etc/runlevels/default"
printf '%s\n' "$HOSTNAME" > "$ROOT/etc/hostname"

cat > "$ROOT/etc/inittab" <<'EOF'
::sysinit:/sbin/openrc sysinit
::sysinit:/sbin/openrc boot
::wait:/sbin/openrc default
::ctrlaltdel:/sbin/reboot
::shutdown:/sbin/openrc shutdown

ttyS0::respawn:/sbin/getty -L ttyS0 115200 vt100
EOF

cat > "$ROOT/etc/resolv.conf" <<'EOF'
nameserver 10.0.2.3
nameserver 1.1.1.1
nameserver 8.8.8.8
EOF

cat > "$ROOT/etc/network/interfaces" <<'EOF'
auto lo
iface lo inet loopback

auto eth0
iface eth0 inet static
    address 10.0.2.15
    netmask 255.255.255.0
    gateway 10.0.2.2
EOF

cat > "$ROOT/usr/local/sbin/vmconsole-mount-modloop" <<'EOF'
#!/bin/sh
set -eu
KVER="$(uname -r)"
[ -d "/lib/modules/$KVER" ] && exit 0
MODLOOP=""
for candidate in /media/*/boot/modloop-* /boot/modloop-*; do
    [ -f "$candidate" ] || continue
    MODLOOP="$candidate"
    break
done
[ -n "$MODLOOP" ] || exit 0
mkdir -p /.modloop
mountpoint -q /.modloop || mount -o loop "$MODLOOP" /.modloop || exit 0
if [ -d /.modloop/modules ]; then
    rm -rf /lib/modules 2>/dev/null || true
    ln -sfn /.modloop/modules /lib/modules
fi
modprobe ext4 2>/dev/null || true
EOF
chmod +x "$ROOT/usr/local/sbin/vmconsole-mount-modloop"

cat > "$ROOT/etc/local.d/vmconsole-net.start" <<'EOF'
#!/bin/sh
/usr/local/sbin/vmconsole-mount-modloop 2>/dev/null || true
ip link set eth0 up 2>/dev/null || true
ip addr show dev eth0 | grep -q 'inet 10\.0\.2\.15/' || ip addr add 10.0.2.15/24 dev eth0 2>/dev/null || true
ip route show default | grep -q . || ip route add default via 10.0.2.2 dev eth0 2>/dev/null || true
mkdir -p /etc
cat > /etc/resolv.conf <<RESOLV
nameserver 10.0.2.3
nameserver 1.1.1.1
nameserver 8.8.8.8
RESOLV
EOF
chmod +x "$ROOT/etc/local.d/vmconsole-net.start"
ln -sf /etc/init.d/local "$ROOT/etc/runlevels/default/local"

cat > "$ROOT/etc/motd" <<'EOF'
Welcome to vmConsole Alpine.

After setup-disk, before rebooting, run:
  mount /dev/sda3 /mnt
  vmconsole-fix-installed-boot /mnt
EOF

cat > "$ROOT/etc/profile.d/vmconsole.sh" <<'EOF'
/usr/local/sbin/vmconsole-mount-modloop 2>/dev/null || true
if [ -t 0 ]; then
    echo
    echo "vmConsole hint: after setup-disk, run: mount /dev/sda3 /mnt && vmconsole-fix-installed-boot /mnt"
    echo
fi
EOF

cat > "$ROOT/usr/local/sbin/vmconsole-fix-installed-boot" <<'EOF'
#!/bin/sh
set -eu
TARGET="${1:-/mnt}"
CMDLINE="rw nomodeset vga=normal console=tty0 console=ttyS0,115200n8 panic=30"
[ -d "$TARGET/etc" ] || { echo "Target does not look mounted: $TARGET" >&2; exit 1; }
mkdir -p "$TARGET/etc/default" "$TARGET/etc/network"

cat > "$TARGET/etc/inittab" <<'INITTAB'
::sysinit:/sbin/openrc sysinit
::sysinit:/sbin/openrc boot
::wait:/sbin/openrc default
::ctrlaltdel:/sbin/reboot
::shutdown:/sbin/openrc shutdown

ttyS0::respawn:/sbin/getty -L ttyS0 115200 vt100
INITTAB

GRUB="$TARGET/etc/default/grub"
touch "$GRUB"
sed -i '/^GRUB_CMDLINE_LINUX/d;/^GRUB_CMDLINE_LINUX_DEFAULT/d;/^GRUB_TERMINAL/d;/^GRUB_SERIAL_COMMAND/d;/^GRUB_GFXMODE/d;/^GRUB_GFXPAYLOAD_LINUX/d' "$GRUB"
cat >> "$GRUB" <<GRUBEOF
GRUB_CMDLINE_LINUX="$CMDLINE"
GRUB_CMDLINE_LINUX_DEFAULT="$CMDLINE"
GRUB_TERMINAL="serial console"
GRUB_SERIAL_COMMAND="serial --unit=0 --speed=115200 --word=8 --parity=no --stop=1"
GRUB_GFXMODE="text"
GRUB_GFXPAYLOAD_LINUX="text"
GRUBEOF

CFG="$TARGET/boot/grub/grub.cfg"
if [ -f "$CFG" ]; then
    cp "$CFG" "$CFG.vmconsole.bak" 2>/dev/null || true
    grep -q '^serial --unit=0 --speed=115200' "$CFG" || sed -i '1iserial --unit=0 --speed=115200 --word=8 --parity=no --stop=1
1iterminal_input serial console
1iterminal_output serial console
1iset gfxpayload=text' "$CFG"
    grep -q 'console=ttyS0' "$CFG" || sed -i "/^[[:space:]]*linux[[:space:]]/ s|$| $CMDLINE|" "$CFG"
fi

for CFG in "$TARGET/boot/extlinux.conf" "$TARGET/boot/syslinux/syslinux.cfg" "$TARGET/boot/syslinux/extlinux.conf"; do
    [ -f "$CFG" ] || continue
    cp "$CFG" "$CFG.vmconsole.bak" 2>/dev/null || true
    grep -q '^SERIAL 0 115200' "$CFG" || sed -i '1iSERIAL 0 115200' "$CFG"
    if grep -qi '^[[:space:]]*append[[:space:]]' "$CFG"; then
        grep -q 'console=ttyS0' "$CFG" || sed -i "/^[[:space:]]*[Aa][Pp][Pp][Ee][Nn][Dd][[:space:]]/ s|$| $CMDLINE|" "$CFG"
    fi
done

cat > "$TARGET/etc/resolv.conf" <<RESOLV
nameserver 10.0.2.3
nameserver 1.1.1.1
nameserver 8.8.8.8
RESOLV
cat > "$TARGET/etc/network/interfaces" <<NET
auto lo
iface lo inet loopback

auto eth0
iface eth0 inet static
    address 10.0.2.15
    netmask 255.255.255.0
    gateway 10.0.2.2
NET
sync
echo "vmConsole installed boot repair done."
EOF
chmod +x "$ROOT/usr/local/sbin/vmconsole-fix-installed-boot"

tar -C "$ROOT" -czf "${HOSTNAME}.apkovl.tar.gz" .
