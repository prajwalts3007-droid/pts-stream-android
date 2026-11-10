# Compact native revision (v5 test track)

## UI and playback

- Device downloads are reached through Profile → My Downloads, not the bottom dock. Saving a video queues it without leaving playback/details. The transfer notification opens Profile.
- Compact movie posters; series → season → episode folders; private on-disk poster cache and full episode metadata. Existing indexes remain readable, and cached library metadata/posters are backfilled while online. Metadata-free legacy entries retain a safe title/episode fallback.
- Delete confirmations have equal-width, aligned buttons and snapshot-scoped folder deletion. Browse grid/list icons sit alongside Search. Removed title totals, explanatory page copy, dashboard machine hostnames and watch-page transport labels/gesture guide.
- Watch actions use aligned icon columns. Season selectors are compact; PiP is an icon. The surrounding watch page uses the selected device theme; video controls remain cinematic/dark like the web player.
- Web-equivalent pinch: continuous 0.5×–3×, snap within 0.04 of 1×, both directions. Fit preserves source aspect ratio; portrait frame follows the web clamp (300dp–560dp, 58% viewport width, maximum 65% viewport height). Seek/brightness/volume/double-tap remain available.
- Screen lock disables gesture controls until explicitly unlocked. Sleep timer: 15/30/60 minutes, end of episode, off; the deadline survives Activity recreation and pauses playback rather than destroying state.
- Audio/subtitle language, zoom memory and autoplay use the existing account preferences API; subtitle size and fit/fill remain device-local. Theme and browse view remain device-local.

## Downloads and offline progress

- Season/unwatched/individual selection skips saved episodes; estimates use known bytes when available, otherwise duration at approximately 2 Mbps. These are estimates, not promises of final media size.
- Wi-Fi-only default, 1–3 concurrent downloads, optional GiB cap, and shared outstanding-space reservations. Explicitly paused jobs never auto-resume when connectivity changes. Process-interrupted jobs restore as Paused.
- Automatic removal of completed saved media is **off by default**. When enabled, only an offline clip that reached playback end is removed, after its player/decoder closes. No remote library deletion.
- Owner-scoped progress journal persists the latest edit per title, overlays cached library state and retries on foreground/normal sync. Clearing history supersedes old progress. Completed-episode selection state is local because the existing server exposes only latest title progress, not a per-episode watched-history endpoint.
- HLS offline preparation remains refused without verified normal-user authorization. Range resumption requires a stable validator and exact byte range; otherwise downloads restart safely. No media is permanently stored on the VPS.
- Diagnostics export was intentionally excluded.

## Native administration

Pipeline limits, GitHub primary/secondary/tertiary and Google configuration, restart operations, and user create/role/enable/disable/delete use existing authenticated APIs. Administrative controls are not rendered to normal users. Mutations require explicit confirmation; pipeline/token/power/user access changes require typed CONFIRM, and power requires the account password. Secrets are not persisted or restored and are cleared on background entry. Self-disable/delete/demotion controls are not exposed.

Read-only authenticated contract checks against the deployed service confirmed pipeline response fields, all three GitHub account roles, Google fields and user fields. Write request bodies match the deployed web client. No production setting/token/user change or restart was used for validation; mutations are exercised only against synthetic mock services. The older backend Go source was not rebuilt or deployed.

## LAN deployment and security

Owned Pi LAN address discovered over authenticated WireGuard SSH: `192.168.29.75`.

- Added independent `/opt/pts-lan/pi-lan-proxy.py`, `/etc/systemd/system/pts-lan-media.service`.
- TLS relay binds only `192.168.29.75:4443`, forwards only `/media` to the unchanged `127.0.0.1:8787` streamer, exposes a small `/lan-health` identity marker, and does not expose account/admin APIs.
- A dedicated certificate for `media.ptsmovies.online` is bundled as a public trust anchor in the app. Its private key stays on the Pi (`/opt/pts-lan/server.key`, mode 600), loaded into the unprivileged service via systemd credentials. No permissive hostname verifier or global trust bypass.
- Public certificate SHA-256 fingerprint: `80:18:A8:67:8C:EC:43:DE:9E:FB:5D:C3:84:A2:DD:9C:B0:25:EF:D3:88:58:BA:03:BA:E8:92:90:85:41:01:07`. Certificate lifetime: 1,095 days from creation; rotate the public trust anchor with an app update before expiry.
- On Wi-Fi, only a signed HTTPS URL for the known media origin can use the verified LAN endpoint. LAN requests contain no session cookie, Authorization or CSRF header. Range/If-Range and the signed query are preserved. LAN certificate/health/initial-response failures use the tunnel; existing player/download VPS fallback remains available. Cancellation of the owning media request cancels the LAN child call too.
- LAN address and preference are configurable in Profile → Downloads & network; only canonical RFC1918 IPv4 addresses are accepted. A changed address also requires updating the Pi service's bind address. Do not expose the relay on a WAN interface.
- No changes to the production binary, media streamer, queue, pipeline runner settings, ZIP/source checks, nginx bulk-delete guard or permanent cloud media storage.

Pi synthetic fixture verification passed: trusted TLS, signed `206` range with exact bytes/Content-Range, bad signature `403`, account API `404`. Fixture was removed automatically. Python relay tests passed. Android TLS/route tests use generated test certificates only.

## Device validation still needed

Emulator results cannot establish phone-to-Pi Wi-Fi reachability, actual home-router behavior, device hardware codecs/PiP, installer consent or OEM background/battery restrictions. Test on the real phone on home Wi-Fi and mobile data; do not uninstall an existing signed install, since that deletes private downloads.

Rollback of the added relay only: `systemctl disable --now pts-lan-media.service` on the Pi. The existing tunnel/streamer remains independent. Disable Prefer local Pi in the app to force internet routing without a server change.
