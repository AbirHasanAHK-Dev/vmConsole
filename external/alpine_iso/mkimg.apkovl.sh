build_apkovl() {
	local _host="$1" _script=""
	for candidate in "$PWD/$apkovl" "$HOME/.mkimage/$apkovl" "$(readlink -f "$scriptdir/$apkovl")"; do
		if [ -f "$candidate" ]; then
			_script="$candidate"
			break
		fi
	done
	[ -n "$_script" ] || die "could not find $apkovl"
	msg "Generating $_host.apkovl.tar.gz"
	(cd "$DESTDIR"; fakeroot "$_script" "$_host")
}

section_apkovl() {
	[ -n "$apkovl" -a -n "$hostname" ] || return 0
	build_section apkovl $hostname $(checksum < "$apkovl")
}
