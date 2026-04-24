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
::wait:/sbin/openrc boot
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

cat > "$ROOT/etc/local.d/vmconsole-net.start" <<'EOF'
#!/bin/sh
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

Network note:
vmConsole uses QEMU user networking. If DHCP fails, the live system uses:
  eth0: 10.0.2.15/24
  gateway: 10.0.2.2
  DNS: 10.0.2.3, 1.1.1.1, 8.8.8.8

Important for installed systems:
After running setup-alpine/setup-disk and before rebooting, run:

  vmconsole-fix-installed-boot /mnt

If you already rebooted and the installed disk shows GRUB then a black screen,
boot the ISO/live system again, mount the installed root at /mnt, then run the
same command.
EOF

cat > "$ROOT/etc/profile.d/vmconsole.sh" <<'EOF'
if [ -t 0 ]; then
    echo
    echo "vmConsole hint: after setup-alpine/setup-disk, run: vmconsole-fix-installed-boot /mnt"
    echo "vmConsole network: static eth0 fallback is 10.0.2.15 via 10.0.2.2"
    echo
fi
EOF

cat > "$ROOT/usr/local/sbin/vmconsole-fix-installed-boot" <<'EOF'
#!/bin/sh
set -eu

SERIAL_CMDLINE="nomodeset vga=normal console=tty0 console=ttyS0,115200n8 earlycon=uart,io,0x3f8,115200 panic=30"
TARGET="${1:-/mnt}"

log() {
    printf '%s\n' "vmconsole-fix-installed-boot: $*"
}

fail() {
    printf '%s\n' "vmconsole-fix-installed-boot: ERROR: $*" >&2
    exit 1
}

set_or_append_var() {
    file="$1"
    key="$2"
    value="$3"
    mkdir -p "$(dirname "$file")"
    touch "$file"
    if grep -q "^${key}=" "$file"; then
        sed -i "s|^${key}=.*|${key}=\"${value}\"|" "$file"
    else
        printf '%s="%s"\n' "$key" "$value" >> "$file"
    fi
}

patch_grub_default() {
    grub_default="$TARGET/etc/default/grub"
    mkdir -p "$(dirname "$grub_default")"
    touch "$grub_default"
    cp "$grub_default" "$grub_default.vmconsole.bak" 2>/dev/null || true
    set_or_append_var "$grub_default" GRUB_CMDLINE_LINUX_DEFAULT "$SERIAL_CMDLINE"
    set_or_append_var "$grub_default" GRUB_CMDLINE_LINUX "$SERIAL_CMDLINE"
    set_or_append_var "$grub_default" GRUB_TERMINAL "serial console"
    set_or_append_var "$grub_default" GRUB_SERIAL_COMMAND "serial --unit=0 --speed=115200 --word=8 --parity=no --stop=1"
    set_or_append_var "$grub_default" GRUB_GFXMODE "text"
    set_or_append_var "$grub_default" GRUB_GFXPAYLOAD_LINUX "text"
    set_or_append_var "$grub_default" GRUB_TIMEOUT "5"
}

patch_grub_cfg_direct() {
    grub_cfg="$TARGET/boot/grub/grub.cfg"
    [ -f "$grub_cfg" ] || return 0
    cp "$grub_cfg" "$grub_cfg.vmconsole.bak" 2>/dev/null || true
    if ! grep -q "console=ttyS0" "$grub_cfg"; then
        sed -i "/^[[:space:]]*linux[[:space:]]/ s|$| $SERIAL_CMDLINE|" "$grub_cfg"
    fi
    if ! grep -q "^serial --unit=0 --speed=115200" "$grub_cfg"; then
        tmp="$grub_cfg.tmp.$$"
        {
            printf '%s\n' 'serial --unit=0 --speed=115200 --word=8 --parity=no --stop=1'
            printf '%s\n' 'terminal_input serial console'
            printf '%s\n' 'terminal_output serial console'
            printf '%s\n' 'set gfxpayload=text'
            cat "$grub_cfg"
        } > "$tmp"
        mv "$tmp" "$grub_cfg"
    fi
    grep -q '^set gfxpayload=text' "$grub_cfg" || sed -i '1iset gfxpayload=text' "$grub_cfg"
}

patch_extlinux() {
    update_conf="$TARGET/etc/update-extlinux.conf"
    if [ -f "$update_conf" ]; then
        cp "$update_conf" "$update_conf.vmconsole.bak" 2>/dev/null || true
        if grep -q '^default_kernel_opts=' "$update_conf"; then
            sed -i "s|^default_kernel_opts=.*|default_kernel_opts=\"$SERIAL_CMDLINE\"|" "$update_conf"
        else
            printf 'default_kernel_opts="%s"\n' "$SERIAL_CMDLINE" >> "$update_conf"
        fi
    fi

    extlinux_cfg="$TARGET/boot/extlinux.conf"
    if [ -f "$extlinux_cfg" ]; then
        cp "$extlinux_cfg" "$extlinux_cfg.vmconsole.bak" 2>/dev/null || true
        if ! grep -q "console=ttyS0" "$extlinux_cfg"; then
            sed -i "/^[[:space:]]*append[[:space:]]/ s|$| $SERIAL_CMDLINE|" "$extlinux_cfg"
        fi
        if ! grep -q '^serial 0 115200' "$extlinux_cfg"; then
            tmp="$extlinux_cfg.tmp.$$"
            {
                printf '%s\n' 'serial 0 115200'
                cat "$extlinux_cfg"
            } > "$tmp"
            mv "$tmp" "$extlinux_cfg"
        fi
    fi
}

patch_inittab() {
    inittab="$TARGET/etc/inittab"
    mkdir -p "$TARGET/etc"
    touch "$inittab"
    if ! grep -q '^ttyS0::respawn:/sbin/getty' "$inittab"; then
        printf '%s\n' 'ttyS0::respawn:/sbin/getty -L ttyS0 115200 vt100' >> "$inittab"
    fi
}

patch_network() {
    mkdir -p "$TARGET/etc/network"
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
}

run_chroot_updates() {
    [ -x "$TARGET/bin/sh" ] || return 0

    for d in dev proc sys; do
        if [ -d "/$d" ] && [ -d "$TARGET/$d" ] && ! mountpoint -q "$TARGET/$d"; then
            mount --bind "/$d" "$TARGET/$d" 2>/dev/null || true
        fi
    done

    if chroot "$TARGET" sh -c 'command -v grub-mkconfig >/dev/null 2>&1 && [ -d /boot/grub ]'; then
        log "regenerating /boot/grub/grub.cfg"
        chroot "$TARGET" grub-mkconfig -o /boot/grub/grub.cfg || true
    fi

    if chroot "$TARGET" sh -c 'command -v update-grub >/dev/null 2>&1 && [ -d /boot/grub ]'; then
        log "running update-grub"
        chroot "$TARGET" update-grub || true
    fi

    if chroot "$TARGET" sh -c 'command -v update-extlinux >/dev/null 2>&1 && [ -f /etc/update-extlinux.conf ]'; then
        log "running update-extlinux"
        chroot "$TARGET" update-extlinux || true
    fi
}

[ -d "$TARGET" ] || fail "target mountpoint does not exist: $TARGET"
[ -d "$TARGET/etc" ] || fail "$TARGET does not look like an installed Alpine root. Mount it first."

log "patching installed system at $TARGET"
patch_grub_default
patch_grub_cfg_direct
patch_extlinux
patch_inittab
patch_network
run_chroot_updates
patch_grub_cfg_direct
patch_extlinux
patch_inittab
log "done. Reboot the VM and boot from disk."
EOF
chmod +x "$ROOT/usr/local/sbin/vmconsole-fix-installed-boot"

tar -C "$ROOT" -czf "${HOSTNAME}.apkovl.tar.gz" .
