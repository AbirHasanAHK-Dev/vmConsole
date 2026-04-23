profile_vmconsole() {
	profile_base
	arch="x86_64"
	profile_abbrev="vmc"
	hostname="vmconsole"
	image_name="alpine"
	output_filename="alpine-x86_64.iso"
	initfs_cmdline="modules=loop,squashfs,sd-mod,usb-storage"
	kernel_cmdline="console=ttyS0,115200n8 earlyprintk=serial,ttyS0,115200"
	syslinux_serial="0 115200"
	syslinux_timeout="20"
	syslinux_prompt="1"
	apkovl="genapkovl-vmconsole.sh"
	apks="$apks openssh-server"
}
