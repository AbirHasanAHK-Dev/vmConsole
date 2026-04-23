build_kernel() {
	local _flavor="$2" _add
	shift 3
	local _pkgs="$@"
	update-kernel \
		$_hostkeys \
		--media \
		--cache-dir "$APKROOT/etc/apk/cache" \
		--keys-dir "$APKROOT/etc/apk/keys" \
		--flavor "$_flavor" \
		--arch "$ARCH" \
		--package "$_pkgs" \
		--feature "$initfs_features" \
		--repositories-file "$APKROOT/etc/apk/repositories" \
		"$DESTDIR"
	for _add in $boot_addons; do
		apk fetch --root "$APKROOT" --quiet --stdout $_add | tar -C "${DESTDIR}" -zx boot/
	done
}

section_kernels() {
	local _f _a _pkgs
	for _f in $kernel_flavors; do
		_pkgs="linux-$_f linux-firmware $modloop_addons"
		for _a in $kernel_addons; do
			_pkgs="$_pkgs $_a-$_f"
		done
		local id=$( (echo "$initfs_features::$_hostkeys" ; apk fetch --root "$APKROOT" --simulate alpine-base $_pkgs | sort) | checksum)
		build_section kernel $ARCH $_f $id $_pkgs
	done
}

build_apks() {
	local _apksdir="$DESTDIR/apks"
	local _archdir="$_apksdir/$ARCH"
	mkdir -p "$_archdir"

	apk fetch --root "$APKROOT" --link --recursive --output "$_archdir" $apks
	if ! ls "$_archdir"/*.apk >/dev/null 2>&1; then
		return 1
	fi

	apk index \
		--root "$APKROOT" \
		--description "$RELEASE" \
		--rewrite-arch "$ARCH" \
		--index "$_archdir"/APKINDEX.tar.gz \
		--output "$_archdir"/APKINDEX.tar.gz \
		"$_archdir"/*.apk
	abuild-sign "$_archdir"/APKINDEX.tar.gz
	touch "$_apksdir/.boot_repository"
}

section_apks() {
	[ -n "$apks" ] || return 0
	build_section apks $ARCH $(apk fetch --root "$APKROOT" --simulate --recursive $apks | sort | checksum)
}

build_syslinux() {
	local _fn
	mkdir -p "$DESTDIR"/boot/syslinux
	apk fetch --root "$APKROOT" --stdout syslinux | tar -C "$DESTDIR" -xz usr/share/syslinux
	for _fn in isohdpfx.bin isolinux.bin ldlinux.c32 libutil.c32 libcom32.c32 mboot.c32; do
		mv "$DESTDIR"/usr/share/syslinux/$_fn "$DESTDIR"/boot/syslinux/$_fn
	done
	rm -rf "$DESTDIR"/usr
}

section_syslinux() {
	[ "$ARCH" = x86 -o "$ARCH" = x86_64 ] || return 0
	[ "$output_format" = "iso" ] || return 0
	build_section syslinux $(apk fetch --root "$APKROOT" --simulate syslinux | sort | checksum)
}

syslinux_gen_config() {
	[ -z "$syslinux_serial" ] || echo "SERIAL $syslinux_serial"
	echo "TIMEOUT ${syslinux_timeout:-10}"
	echo "PROMPT ${syslinux_prompt:-1}"
	echo "DEFAULT ${kernel_flavors%% *}"

	local _f _initrd
	for _f in $kernel_flavors; do
		_initrd="/boot/initramfs-$_f"
		cat <<- EOF

		LABEL $_f
			MENU LABEL Linux $_f
			KERNEL /boot/vmlinuz-$_f
			INITRD $_initrd
			APPEND $initfs_cmdline $kernel_cmdline
		EOF
	done
}

build_syslinux_cfg() {
	local syslinux_cfg="$1"
	mkdir -p "${DESTDIR}/$(dirname $syslinux_cfg)"
	syslinux_gen_config > "${DESTDIR}"/$syslinux_cfg
}

section_syslinux_cfg() {
	syslinux_cfg=""
	if [ "$ARCH" = x86 -o "$ARCH" = x86_64 ]; then
		[ ! "$output_format" = "iso" ] || syslinux_cfg="boot/syslinux/syslinux.cfg"
	fi
	[ -n "$syslinux_cfg" ] || return 0
	build_section syslinux_cfg $syslinux_cfg $(syslinux_gen_config | checksum)
}

gen_volid() {
	printf "%s" "alpine-${profile_abbrev:-$PROFILE} ${RELEASE%_rc*} $ARCH" | cut -c1-32
}

create_image_iso() {
	local ISO="${OUTDIR}/${output_filename}"
	local _isolinux="
		-isohybrid-mbr ${DESTDIR}/boot/syslinux/isohdpfx.bin
		-eltorito-boot boot/syslinux/isolinux.bin
		-eltorito-catalog boot/syslinux/boot.cat
		-no-emul-boot
		-boot-load-size 4
		-boot-info-table
	"

	xorrisofs \
		-quiet \
		-output ${ISO} \
		-full-iso9660-filenames \
		-joliet \
		-rational-rock \
		-sysid LINUX \
		-volid "$(gen_volid)" \
		$_isolinux \
		-follow-links \
		${iso_opts} \
		${DESTDIR}
}

profile_base() {
	kernel_flavors="lts"
	initfs_cmdline="modules=loop,squashfs,sd-mod,usb-storage quiet"
	initfs_features="ata base cdrom ext4 mmc nvme raid scsi squashfs usb virtio"
	apks="alpine-base busybox e2fsprogs openssh openssl tzdata wget"
	hostname="alpine"
	image_ext="iso"
	output_format="iso"
}
