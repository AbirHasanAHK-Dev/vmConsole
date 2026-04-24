profile_vmconsole() {
	profile_base
	arch="x86_64"
	kernel_flavors="virt"
	profile_abbrev="vmc"
	hostname="vmconsole"
	image_name="alpine"
	output_filename="alpine-x86_64.iso"
	initfs_cmdline="modules=loop,squashfs,sd-mod,usb-storage,virtio_pci,virtio_scsi,virtio_blk,virtio_net"
	kernel_cmdline="nomodeset vga=normal console=tty0 console=ttyS0,115200n8 earlycon=uart,io,0x3f8,115200 earlyprintk=serial,ttyS0,115200 ignore_loglevel loglevel=8 panic=30"
	syslinux_serial="0 115200"
	syslinux_timeout="20"
	syslinux_prompt="1"
	apkovl="genapkovl-vmconsole.sh"
	apks="$apks openssh-server e2fsprogs sfdisk syslinux util-linux"
}
